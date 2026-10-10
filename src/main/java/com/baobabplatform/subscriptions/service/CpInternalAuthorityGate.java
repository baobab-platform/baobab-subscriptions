package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.json.Json;
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
                || !tokenFile.isAbsolute()) {
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
        try {
            if (tenant == null || !tenant.matches("tn_[a-z0-9]+")
                    || subscription == null || !subscription.matches("sub_[a-z0-9]+")
                    || reference == null || reference.length() < 3 || reference.length() > 128) {
                return false;
            }
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (token.isEmpty() || token.length() > 8192 || token.indexOf(' ') >= 0
                    || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
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
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES) {
                return false;
            }
            JsonNode result = Json.mapper().readTree(response.body());
            if (!result.isObject() || !result.path("eligible").isBoolean()
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
                    && !expires.isAfter(evaluated.plusSeconds(5))
                    && result.path("policy_reference").asText().length() >= 10;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Absence of credential, CP outage, malformed response or stale
            // policy must be a denial, never a fallback to classification.
            return false;
        }
    }
}
