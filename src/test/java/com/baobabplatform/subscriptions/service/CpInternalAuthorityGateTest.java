package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Exercise a real loopback HTTP request, not a mocked positive policy boolean. */
final class CpInternalAuthorityGateTest {
    @Test
    void authenticatedBoundCurrentPolicyIsRequiredAndRevocationDenies() throws Exception {
        Instant now = Instant.parse("2026-10-10T15:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var approved = new AtomicBoolean(true);
        String tenant = "tn_synthaccount001";
        String product = "sub_synthproduct001";
        String reference = "adm_synthadmission001";
        Path credential = Files.createTempFile("cp-iam-workload-", ".jwt");
        String token = UUID.randomUUID().toString();
        Files.writeString(credential, token, StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/subscriptions/v1/", exchange -> {
            boolean authenticated = ("Bearer " + token).equals(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            boolean bound = exchange.getRequestURI().getRawPath().contains("/tenants/" + tenant + "/product-subscriptions/" + product)
                    && exchange.getRequestURI().getRawQuery().contains(reference);
            String payload = "{\"tenant_id\":\"" + tenant + "\",\"product_subscription_id\":\"" + product
                    + "\",\"classification_reference\":\"" + reference + "\",\"eligible\":" + approved.get()
                    + ",\"evaluated_at\":\"" + now + "\",\"expires_at\":\"" + now.plusSeconds(3)
                    + "\",\"policy_reference\":\"ADR-BCP-017-PEO-02D\"}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authenticated && bound ? 200 : 403, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        try {
            var client = new CpInternalAuthorityGate(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), credential,
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), clock);
            assertTrue(client.currentlyAuthorised(tenant, product, reference));
            assertFalse(client.currentlyAuthorised(tenant, product, "adm_other"));
            approved.set(false);
            assertFalse(client.currentlyAuthorised(tenant, product, reference),
                    "a revoked/current negative decision must deny despite earlier positive");
            Files.delete(credential);
            assertFalse(client.currentlyAuthorised(tenant, product, reference),
                    "a missing/rotated workload token file must never grant INTERNAL billing");
        } finally {
            server.stop(0);
            Files.deleteIfExists(credential);
        }
    }
}
