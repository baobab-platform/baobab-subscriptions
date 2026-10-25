package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import com.baobabplatform.subscriptions.provider.KillBillAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ADR-SHARED-044: CP-owned current ProductSubscription workload read and
 * operation-bound DENY-only commercial preflight.
 *
 * <p>This port uses a CP-audience IAM token loaded for EACH request. An event,
 * token possession, local billing projection, or generic HTTP 200 cannot grant
 * financial authority. The Shared v1 commercial decision explicitly has NO
 * authorised outcome; approveDispatch always returns empty.
 */
public final class CpCurrentSubscriptionAuthority implements ProviderWorkScheduler.CpAuthority {
    private static final String SNAPSHOT_POLICY =
            "ADR-SHARED-011/ADR-BCP-017/ADR-SUB-0003/current:v1";
    private static final String PREFLIGHT_POLICY =
            "ADR-SHARED-011/ADR-BCP-017/ADR-SUB-0015/financial-preflight:v1";
    private static final Set<String> CURRENT_FIELDS = Set.of(
            "tenant_id","product_subscription_id","product_id","status",
            "authoritative_revision","classification","primary_organisation_id",
            "evaluated_at","expires_at","policy_reference");
    private static final Set<String> CLASSIFICATION_FIELDS = Set.of(
            "classification_id","subscription_type","classification_source","classification_reference");
    private static final Set<String> DECISION_FIELDS = Set.of(
            "tenant_id","product_subscription_id","authoritative_revision","operation_id",
            "outcome","reason_code","evaluated_at","expires_at","policy_reference");
    private final BillingStore store;
    private final URI base;
    private final Path token;
    private final HttpClient http;
    private final Clock clock;

    public CpCurrentSubscriptionAuthority(BillingStore store, URI base,
                                           Path tokenFile, HttpClient http, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.base = Objects.requireNonNull(base);
        this.token = Objects.requireNonNull(tokenFile);
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        boolean loopback = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if ((! "https".equals(base.getScheme()) && !("http".equals(base.getScheme()) && loopback))
                || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || base.getHost() == null
                || !tokenFile.isAbsolute()
                || http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("current CP authority needs HTTPS and explicit IAM credential file");
        }
    }

    public static CpCurrentSubscriptionAuthority create(BillingStore store, URI base, Path token) {
        return new CpCurrentSubscriptionAuthority(store, base, token,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    private static boolean exactFields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!names.contains(fields.next())) return false;
        for (String name : names) if (!node.has(name)) return false;
        return true;
    }

    private boolean fresh(JsonNode result) {
        try {
            Instant now = clock.instant();
            Instant evaluated = Instant.parse(result.path("evaluated_at").asText());
            Instant expires = Instant.parse(result.path("expires_at").asText());
            return !evaluated.isAfter(now.plusSeconds(1))
                    && !evaluated.isBefore(now.minusSeconds(5))
                    && expires.isAfter(now)
                    && expires.isAfter(evaluated)
                    && !expires.isAfter(evaluated.plusSeconds(5));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private byte[] credential() throws IOException {
        byte[] value;
        try (var input = Files.newInputStream(token)) {
            value = input.readNBytes(8193);
        }
        if (value.length == 0 || value.length > 8192) throw new IOException("invalid IAM workload credential");
        String bearer = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(value)).toString().trim();
        if (bearer.isEmpty() || bearer.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IOException("invalid workload credential grammar");
        }
        return ("Bearer " + bearer).getBytes(StandardCharsets.US_ASCII);
    }

    private JsonNode exchange(String path, byte[] post) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // No user-controlled query or redirected bearer. Resource IDs are
            // validated by local billing persistence before path construction.
            String root = base.toString().replaceAll("/+$","");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + path))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", new String(credential(), StandardCharsets.US_ASCII))
                    .header("Accept","application/json").header("Cache-Control","no-store");
            HttpRequest request = post == null ? builder.GET().build()
                    : builder.header("Content-Type","application/json")
                             .POST(HttpRequest.BodyPublishers.ofByteArray(post)).build();
            pending = http.sendAsync(request, ignored -> new BoundedJsonBodySubscriber());
            HttpResponse<byte[]> response = pending.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body().length > 16384) return null;
            return Json.mapper().readTree(response.body());
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException | RuntimeException invalid) {
            if (invalid instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static String prefix(Projection projection) {
        return "/internal/subscriptions/v1/tenants/" + projection.tenantId()
                + "/product-subscriptions/" + projection.productSubscriptionId();
    }

    private Projection projection(ProviderOperation op) {
        return store.transact(tx -> tx.projection(op.tenantId(), op.billingSubscriptionId()).orElse(null));
    }

    private JsonNode verifiedCurrent(ProviderOperation op, Projection p) {
        if (p == null || !p.tenantId().equals(op.tenantId())
                || !p.billingSubscriptionId().equals(op.billingSubscriptionId())
                || p.authoritativeRevision() < op.authoritativeRevision()
                || !"KILL_BILL".equals(op.providerKind())
                || p.provider().simulated() || !p.billingPolicy().billingRequired()) return null;
        JsonNode result = exchange(prefix(p) + "/current", null);
        if (!exactFields(result, CURRENT_FIELDS) || !fresh(result)
                || !SNAPSHOT_POLICY.equals(result.path("policy_reference").asText())
                || !p.tenantId().equals(result.path("tenant_id").asText())
                || !p.productSubscriptionId().equals(result.path("product_subscription_id").asText())
                || !p.productId().equals(result.path("product_id").asText())
                || result.path("authoritative_revision").asLong(-1) != p.authoritativeRevision()
                || !"ACTIVE".equals(result.path("status").asText())
                || !result.path("primary_organisation_id").isTextual()
                || result.path("primary_organisation_id").asText().isBlank()) return null;
        JsonNode classification = result.path("classification");
        if (!exactFields(classification, CLASSIFICATION_FIELDS)
                || !"COMMERCIAL".equals(classification.path("subscription_type").asText())
                || !p.classification().classificationId().equals(classification.path("classification_id").asText())
                || !p.classification().classificationReference().equals(
                        classification.path("classification_reference").asText())) return null;
        return result;
    }

    @Override public boolean permitsObservation(ProviderOperation op) {
        Objects.requireNonNull(op);
        return verifiedCurrent(op, projection(op)) != null;
    }

    @Override public Optional<KillBillAdapter.AuthorisedTerms> approveDispatch(ProviderOperation op) {
        Objects.requireNonNull(op);
        Projection p = projection(op);
        if (p == null || op.authoritativeRevision() != p.authoritativeRevision()
                || verifiedCurrent(op, p) == null) return Optional.empty();
        try {
            var req = Json.mapper().createObjectNode()
                    .put("tenant_id", op.tenantId())
                    .put("product_subscription_id",p.productSubscriptionId())
                    .put("authoritative_revision",op.authoritativeRevision())
                    .put("operation_id",op.operationId().toString())
                    .put("operation_kind",op.operationKind())
                    .put("provider_kind",op.providerKind());
            JsonNode decision = exchange(prefix(p) + "/commercial-authority",
                    Json.mapper().writeValueAsBytes(req));
            if (!exactFields(decision, DECISION_FIELDS) || !fresh(decision)
                    || !PREFLIGHT_POLICY.equals(decision.path("policy_reference").asText())
                    || !op.tenantId().equals(decision.path("tenant_id").asText())
                    || !p.productSubscriptionId().equals(decision.path("product_subscription_id").asText())
                    || op.authoritativeRevision() != decision.path("authoritative_revision").asLong(-1)
                    || !op.operationId().toString().equals(decision.path("operation_id").asText())
                    || !"DENIED".equals(decision.path("outcome").asText())) return Optional.empty();
            // Version 1 has no AUTHORISED result by contract.
            return Optional.empty();
        } catch (IOException error) {
            return Optional.empty();
        }
    }
}
