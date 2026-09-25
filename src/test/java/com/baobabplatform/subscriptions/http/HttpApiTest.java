package com.baobabplatform.subscriptions.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The HTTP boundary: workload identity only (right issuer, audience, actor type,
 * client and scope), Idempotency-Key on every mutation, RFC 9457 problems,
 * correlation IDs and health endpoints.
 */
class HttpApiTest {
    private static final URI ISSUER = URI.create("https://iam.baobab.test/realms/baobab");
    private static RSAKey key;
    private static RSAKey otherKey;
    private static HttpApi api;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        WorkloadAuthenticator auth = new WorkloadAuthenticator(ISSUER, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                "baobab-subscriptions", Set.of("baobab-control-plane"));
        InMemoryBillingStore store = new InMemoryBillingStore();
        BillingService billing = new BillingService(store, new TemporaryProvider(), new Fixtures.RecordingPayments(),
                BillingPolicies.load(), Fixtures.CLOCK);
        api = new HttpApi(0, billing, auth, store, new TemporaryProvider(), "development");
        api.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        api.close();
        client.close();
    }

    private static String token(RSAKey signer, Consumer<JWTClaimsSet.Builder> edit) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER.toString()).subject("service-account-cp")
                .audience("baobab-subscriptions").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString()).claim("actor_type", "workload").claim("azp", "baobab-control-plane")
                .claim("scope", "billing:manage billing:read usage:record");
        edit.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private static String token() throws Exception {
        return token(key, c -> { });
    }

    private static HttpResponse<String> call(String method, String path, String bearer, String idempotencyKey, byte[] body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> ensure(String bearer, String key, byte[] body) throws Exception {
        return call("POST", "/v1/billing-projections", bearer, key, body, Map.of());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(""));
        JsonNode problem = Json.mapper().readTree(response.body());
        assertEquals(code, problem.get("code").asText(), response.body());
        assertTrue(Contracts.problems(Contracts.PROBLEM, problem).isEmpty(), Contracts.problems(Contracts.PROBLEM, problem).toString());
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void healthEndpointsNeedNoToken() throws Exception {
        assertEquals(200, call("GET", "/health/live", null, null, null, Map.of()).statusCode());
        HttpResponse<String> ready = call("GET", "/health/ready", null, null, null, Map.of());
        assertEquals(200, ready.statusCode());
        JsonNode body = Json.mapper().readTree(ready.body());
        assertEquals("READY", body.get("status").asText());
        assertEquals("NOT_CONFIGURED", body.get("commercial_billing").asText(), "a simulated provider never claims commercial readiness");
        assertTrue(body.at("/billing_provider/simulated").asBoolean());
    }

    @Test
    void onlyWorkloadIdentityIsAccepted() throws Exception {
        byte[] body = Fixtures.acmeCommercial();
        assertProblem(ensure(null, newKey(), body), 401, "AUTH_TOKEN_REQUIRED");
        assertProblem(ensure("static-shared-secret", newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(otherKey, c -> { }), newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(key, c -> c.issuer("https://iam.other.test/realms/x")), newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(key, c -> c.audience("baobab-control-plane")), newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(key, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(60)))), newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(key, c -> c.expirationTime(Date.from(Instant.now().plusSeconds(3600)))), newKey(), body), 401, "AUTH_TOKEN_INVALID");
        assertProblem(ensure(token(key, c -> c.claim("actor_type", "human")), newKey(), body), 403, "AUTHORIZATION_DENIED");
        assertProblem(ensure(token(key, c -> c.claim("azp", "baobab-client-portal")), newKey(), body), 403, "AUTHORIZATION_DENIED");
        assertProblem(ensure(token(key, c -> c.claim("scope", "billing:read usage:record")), newKey(), body), 403, "AUTHORIZATION_DENIED");
    }

    @Test
    void mutationsNeedAnIdempotencyKeyAndReplay() throws Exception {
        String bearer = token();
        assertProblem(ensure(bearer, null, Fixtures.acmeCommercial()), 400, "INVALID_IDEMPOTENCY_KEY");
        assertProblem(ensure(bearer, "short", Fixtures.acmeCommercial()), 400, "INVALID_IDEMPOTENCY_KEY");

        String k = newKey();
        HttpResponse<String> created = ensure(bearer, k, Fixtures.acmeCommercial());
        assertEquals(201, created.statusCode(), created.body());
        JsonNode projection = Json.mapper().readTree(created.body());
        assertTrue(Contracts.problems(Contracts.def(Contracts.BILLING, "BillingProjection"), projection).isEmpty());
        assertEquals("BLOCKED", projection.at("/readiness/status").asText());

        HttpResponse<String> replay = ensure(bearer, k, Fixtures.acmeCommercial());
        assertEquals(201, replay.statusCode());
        assertEquals("true", replay.headers().firstValue("Idempotent-Replayed").orElse(""));
        assertEquals(created.body(), replay.body());

        String id = projection.get("billing_subscription_id").asText();
        HttpResponse<String> read = call("GET", "/v1/tenants/" + Fixtures.ACME_TENANT + "/billing-projections/" + id, bearer, null, null, Map.of());
        assertEquals(200, read.statusCode());
        assertProblem(call("GET", "/v1/tenants/" + Fixtures.ZURI_TENANT + "/billing-projections/" + id, bearer, null, null, Map.of()),
                404, "BILLING_PROJECTION_NOT_FOUND");
        HttpResponse<String> byProduct = call("GET", "/v1/tenants/" + Fixtures.ACME_TENANT + "/product-subscriptions/sub_01k9acmexbt/billing-projection",
                bearer, null, null, Map.of());
        assertEquals(200, byProduct.statusCode());

        HttpResponse<String> usage = call("POST", "/v1/billing-projections/" + id + "/usage", bearer, newKey(),
                Fixtures.usage(Fixtures.ACME_TENANT, "http-1"), Map.of());
        assertEquals(201, usage.statusCode(), usage.body());
    }

    @Test
    void problemsAndCorrelation() throws Exception {
        String correlation = "0192a1b0-7c3e-7a10-8000-00000000abcd";
        HttpResponse<String> invalid = call("POST", "/v1/billing-projections", token(), newKey(), Fixtures.json("{\"tenant_id\":\"x\"}"),
                Map.of("X-Correlation-ID", correlation));
        assertProblem(invalid, 400, "VALIDATION_FAILED");
        assertEquals(correlation, invalid.headers().firstValue("X-Correlation-ID").orElse(""));
        assertEquals(correlation, Json.mapper().readTree(invalid.body()).get("correlation_id").asText());
        assertProblem(call("GET", "/v1/nothing", token(), null, null, Map.of()), 404, "ROUTE_NOT_FOUND");
        assertProblem(call("DELETE", "/v1/billing-projections", token(), null, null, Map.of()), 405, "METHOD_NOT_ALLOWED");
        assertProblem(call("POST", "/v1/billing-projections", token(), newKey(), new byte[70 * 1024], Map.of()), 413, "PAYLOAD_TOO_LARGE");
    }
}
