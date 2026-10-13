package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PEO-02D current INTERNAL policy adapter, evaluated on every operation.
 *
 * A workload-sidecar must materialise a short-lived OAuth token for the CP
 * audience and subscription:internal-authority scope in a readable file.
 * The token is read anew for each invocation and never logged or cached.
 * Transport redirects are disabled to prevent bearer-token forwarding.
 */
public final class CpInternalAuthorityGate implements InternalAuthorityGate {
    private static final int MAX_RESPONSE_BYTES = 16384;

    private final URI controlPlaneBase;
    private final Path tokenFile;
    private final HttpClient http;
    private final Clock clock;

    public CpInternalAuthorityGate(URI controlPlaneBase, Path tokenFile, HttpClient http, Clock clock) {
        this.controlPlaneBase = Objects.requireNonNull(controlPlaneBase);
        this.tokenFile = Objects.requireNonNull(tokenFile);
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        if (!"https".equals(controlPlaneBase.getScheme())
                && !("http".equals(controlPlaneBase.getScheme())
                && ("localhost".equals(controlPlaneBase.getHost()) || "127.0.0.1".equals(controlPlaneBase.getHost())))) {
            throw new IllegalArgumentException("Control Plane policy endpoint requires TLS except localhost");
        }
        if (controlPlaneBase.getUserInfo() != null || controlPlaneBase.getRawQuery() != null
                || controlPlaneBase.getRawFragment() != null || controlPlaneBase.getHost() == null
                || !tokenFile.isAbsolute() || http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Control Plane URL and identity token path must be explicit");
        }
    }

    public static CpInternalAuthorityGate create(URI cp, Path credentials) {
        return new CpInternalAuthorityGate(cp, credentials,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    @Override
    public boolean currentlyAuthorised(String tenant, String subscription, String reference) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            // Reuse the owned response-property grammars for the GET tuple;
            // Shared defines no separate request schema for this endpoint.
            if (!Contracts.validEntry("tenant_id", Json.mapper().valueToTree(tenant))
                    || !Contracts.validEntry("product_subscription_id", Json.mapper().valueToTree(subscription))
                    || !Contracts.validEntry("classification_reference", Json.mapper().valueToTree(reference))) {
                return false;
            }
            byte[] credential;
            try (var input = Files.newInputStream(tokenFile)) {
                credential = input.readNBytes(8193);
            }
            if (credential.length > 8192) return false;
            String token = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(credential)).toString().trim();
            if (token.isEmpty() || token.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                return false;
            }
            String base = controlPlaneBase.toString().replaceAll("/+$", "");
            URI uri = URI.create(base + "/internal/subscriptions/v1/tenants/" + tenant
                    + "/product-subscriptions/" + subscription
                    + "/internal-authority?classification_reference="
                    + URLEncoder.encode(reference, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET().timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
                    .build();
            // The complete-response future deadline includes a slow body after
            // prompt headers; the subscriber bounds retention before copying.
            exchange = http.sendAsync(request, ignored -> new BoundedJsonBodySubscriber());
            HttpResponse<byte[]> response = exchange.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES) {
                return false;
            }
            JsonNode result = Json.mapper().readTree(response.body());
            if (result == null || !Contracts.validEntry("internal_authority", result)
                    || !result.path("eligible").booleanValue()
                    || !tenant.equals(result.path("tenant_id").asText())
                    || !subscription.equals(result.path("product_subscription_id").asText())
                    || !reference.equals(result.path("classification_reference").asText())) {
                return false;
            }
            Instant now = clock.instant();
            Instant evaluated = Instant.parse(result.path("evaluated_at").asText());
            Instant expires = Instant.parse(result.path("expires_at").asText());
            // Do not accept replayed responses, future-dated leases or policy
            // responses that try to grant a long validity window.
            return !evaluated.isAfter(now.plusSeconds(1))
                    && !evaluated.isBefore(now.minusSeconds(5))
                    && expires.isAfter(now)
                    && expires.isAfter(evaluated)
                    && !expires.isAfter(evaluated.plusSeconds(5))
                    && result.path("policy_reference").asText().length() >= 10;
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Absence of credential, CP outage, malformed response or stale
            // policy must be a denial, never a fallback to classification.
            return false;
        } finally {
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
        }
    }
}
