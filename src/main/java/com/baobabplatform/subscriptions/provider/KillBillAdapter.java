package com.baobabplatform.subscriptions.provider;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Kill Bill 0.24.x HTTP anti-corruption layer (ADR-SUB-0015).
 *
 * <p>External keys are deterministic within a provider tenant. A read returns
 * provider-local observations, never a CP lifecycle transition or payment
 * settlement. Mutations require explicitly authorised and revision-fenced
 * commercial terms from the controlled worker; normal BillingProvider
 * methods intentionally cannot create, suspend, bill or charge.
 *
 * <p>No default network tenant and no shared credentials across CP tenants.
 * Provider credentials are re-read from mounted secret files for rotation.
 */
public final class KillBillAdapter implements BillingProvider, AutoCloseable {
    private static final String SUBSCRIPTIONS = "/1.0/kb/subscriptions";
    private static final int MAX_BYTES = 128 * 1024;
    private final HttpClient client;
    private final Map<String, TenantEndpoint> tenants;

    /** Explicit CP-tenant to Kill Bill tenant + deployment binding. */
    public record TenantEndpoint(URI base, Path usernameFile, Path passwordFile,
                                 Path apiKeyFile, Path apiSecretFile) {
        public TenantEndpoint {
            Objects.requireNonNull(base);
            Objects.requireNonNull(usernameFile);
            Objects.requireNonNull(passwordFile);
            Objects.requireNonNull(apiKeyFile);
            Objects.requireNonNull(apiSecretFile);
            if (!base.isAbsolute() || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !base.getPath().isEmpty()
                    && !"/".equals(base.getPath())) {
                throw new IllegalArgumentException("Kill Bill endpoint must be an origin without credentials or path");
            }
            String scheme = base.getScheme();
            boolean loopback = "localhost".equalsIgnoreCase(base.getHost())
                    || "127.0.0.1".equals(base.getHost())
                    || "::1".equals(base.getHost()) || "[::1]".equals(base.getHost());
            if (!"https".equalsIgnoreCase(scheme)
                    && !("http".equalsIgnoreCase(scheme) && loopback)) {
                throw new IllegalArgumentException("Kill Bill requires HTTPS except on loopback integration tests");
            }
        }
    }

    /** Material result from independently authorised current CP + terms read. */
    public record AuthorisedTerms(String tenantId, String billingSubscriptionId,
                                  long revision, UUID billingAccountId,
                                  String planName, UUID cpDecisionId) {
        public AuthorisedTerms {
            if (tenantId == null || tenantId.isBlank()
                    || billingSubscriptionId == null || billingSubscriptionId.isBlank()
                    || revision < 1 || billingAccountId == null || cpDecisionId == null
                    || planName == null || !planName.matches("[A-Za-z0-9._:-]{3,120}")) {
                throw new IllegalArgumentException("approved commercial terms require CP provenance, account and plan");
            }
        }
    }

    public KillBillAdapter(Map<String, TenantEndpoint> tenants) {
        if (tenants == null || tenants.isEmpty() || tenants.keySet().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("explicit provider-tenant bindings required");
        }
        this.tenants = Map.copyOf(tenants);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String kind() { return "KILLBILL"; }
    @Override public boolean simulated() { return false; }
    @Override public ProviderCapabilities capabilities() {
        // No automatic account, usage or payment operations are certified.
        return new ProviderCapabilities(false, false, false, false, false,
                true, false, true);
    }

    @Override public String ensureSubscription(Projection projection) {
        throw new UnsupportedOperationException("Kill Bill writes require authorised durable dispatch");
    }
    @Override public void suspend(Projection projection) {
        throw new UnsupportedOperationException("provider suspension is not qualified");
    }
    @Override public void cancel(Projection projection) {
        throw new UnsupportedOperationException("provider cancellation is not qualified");
    }
    @Override public void recordUsage(Projection projection, UsageRecord usage) {
        throw new UnsupportedOperationException("provider usage charging is not qualified");
    }

    /** A stable provider-owned key, scoped by its Kill Bill tenant credentials. */
    public static String externalKey(String subscriptionId) {
        if (subscriptionId == null || !subscriptionId.matches("bsub_[a-z0-9_]{3,63}")) {
            throw new IllegalArgumentException("invalid canonical Baobab billing subscription ID");
        }
        return "baobab:" + subscriptionId;
    }

    @Override public ProviderOutcome observeSubscription(Projection p, ProviderOperationIntent intent) {
        Objects.requireNonNull(p);
        Objects.requireNonNull(intent);
        if (!p.tenantId().equals(intent.tenantId())
                || !p.billingSubscriptionId().equals(intent.billingSubscriptionId())
                || p.authoritativeRevision() < intent.authoritativeRevision()
                || !p.billingPolicy().billingRequired() || p.provider().simulated()) {
            return ProviderOutcome.failure(ProviderOutcome.Status.PERMANENT_FAILURE, "UNAUTHORISED_CONTEXT");
        }
        String key = externalKey(p.billingSubscriptionId());
        URI url = uri(endpoint(intent.tenantId()), SUBSCRIPTIONS + "?externalKey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = client.send(request(endpoint(intent.tenantId()), url, "GET", null),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 404) {
                // Absence now is not evidence the earlier POST could not later commit.
                return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_RESOURCE_NOT_FOUND");
            }
            if (status == 401 || status == 403) {
                return ProviderOutcome.failure(ProviderOutcome.Status.PERMANENT_FAILURE, "PROVIDER_AUTHORIZATION_DENIED");
            }
            if (status != 200 || response.body().length > MAX_BYTES) {
                return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_OBSERVATION_UNAVAILABLE");
            }
            JsonNode object = Json.mapper().readTree(response.body());
            String foundKey = object.path("externalKey").asText();
            String id = object.path("subscriptionId").asText();
            String state = object.path("state").asText();
            if (!key.equals(foundKey) || !uuid(id)
                    || !("ACTIVE".equals(state) || "PENDING".equals(state))) {
                return ProviderOutcome.failure(ProviderOutcome.Status.CONFLICT, "PROVIDER_IDENTITY_OR_STATE_CONFLICT");
            }
            return ProviderOutcome.alreadyConverged(id);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_OBSERVATION_INTERRUPTED");
        } catch (IOException | RuntimeException unavailable) {
            return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_OBSERVATION_UNAVAILABLE");
        }
    }

    /**
     * Performs at most ONE provider POST. Caller must have acquired a durable
     * SENT lease and an IAM/CP-approved commercial decision before this call.
     * Timeouts, conflicts and ambiguous responses are NEVER blind-retried.
     */
    public ProviderOutcome ensureAuthorised(Projection projection, ProviderOperationIntent intent,
                                            AuthorisedTerms terms) {
        Objects.requireNonNull(projection);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(terms);
        if (!terms.tenantId().equals(intent.tenantId())
                || !terms.billingSubscriptionId().equals(intent.billingSubscriptionId())
                || terms.revision() != intent.authoritativeRevision()
                || projection.authoritativeRevision() != terms.revision()
                || !projection.tenantId().equals(terms.tenantId())
                || !projection.billingSubscriptionId().equals(terms.billingSubscriptionId())
                || !projection.billingPolicy().billingRequired()
                || projection.provider().simulated()) {
            return ProviderOutcome.failure(ProviderOutcome.Status.PERMANENT_FAILURE, "CP_AUTHORITY_MISMATCH");
        }
        // First observe the deterministic external key to prevent an
        // accidental second subscription in a previously completed ensure.
        ProviderOutcome observed = observeSubscription(projection, intent);
        if (observed.isConverged() || observed.status() == ProviderOutcome.Status.CONFLICT) {
            return observed;
        }
        if (!"PROVIDER_RESOURCE_NOT_FOUND".equals(observed.reasonCode())) {
            return observed;
        }
        ObjectNode payload = Json.mapper().createObjectNode()
                .put("accountId", terms.billingAccountId().toString())
                .put("externalKey", externalKey(projection.billingSubscriptionId()))
                .put("planName", terms.planName());
        URI url = uri(endpoint(intent.tenantId()), SUBSCRIPTIONS + "?renameKeyIfExistsAndUnused=false");
        try {
            HttpResponse<byte[]> response = client.send(request(endpoint(intent.tenantId()), url,
                    "POST", Json.mapper().writeValueAsBytes(payload)), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            // 201 may return only a bundle Location. Never confuse bundle ID
            // with subscription ID. Read by external key to confirm identity.
            if (status == 201 || status == 200 || status == 204 || status == 409) {
                return observeSubscription(projection, intent);
            }
            if (status == 400 || status == 401 || status == 403 || status == 422) {
                return ProviderOutcome.failure(ProviderOutcome.Status.PERMANENT_FAILURE, "PROVIDER_REQUEST_REJECTED");
            }
            // Even 429/5xx cannot prove the POST did not commit remotely.
            return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_DISPATCH_UNCERTAIN");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_DISPATCH_INTERRUPTED");
        } catch (IOException | RuntimeException unavailable) {
            return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_DISPATCH_UNCERTAIN");
        }
    }

    @Override public boolean healthy() {
        // A Kill Bill tenant listing is not authorization to operate another
        // tenant. Health is not billing readiness; no credential-less probing.
        return !tenants.isEmpty();
    }

    private TenantEndpoint endpoint(String tenant) {
        TenantEndpoint endpoint = tenants.get(tenant);
        if (endpoint == null) throw new IllegalArgumentException("no approved Kill Bill mapping for tenant");
        return endpoint;
    }
    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static URI uri(TenantEndpoint e, String path) {
        return e.base().resolve(path);
    }
    private static String readSecret(Path file) throws IOException {
        // Never retain provider secrets in immutable Config or print them.
        String s = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (s.isBlank() || s.contains("\r") || s.contains("\n")) {
            throw new IOException("invalid provider secret file");
        }
        return s;
    }
    private static HttpRequest request(TenantEndpoint e, URI url, String method, byte[] body) throws IOException {
        String credentials = readSecret(e.usernameFile()) + ":" + readSecret(e.passwordFile());
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + basic)
                .header("X-Killbill-ApiKey", readSecret(e.apiKeyFile()))
                .header("X-Killbill-ApiSecret", readSecret(e.apiSecretFile()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.header("X-Killbill-CreatedBy", "baobab-subscriptions");
            return builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        }
        return builder.GET().build();
    }
    @Override public void close() { client.close(); }
}
