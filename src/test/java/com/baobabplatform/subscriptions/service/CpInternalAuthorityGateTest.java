package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Exercise a real loopback HTTP request, not a mocked positive policy boolean. */
final class CpInternalAuthorityGateTest {
    private static String decision(Instant now) {
        return """
            {"tenant_id":"tn_synthetic123","product_subscription_id":"sub_synthetic123",
             "classification_reference":"synthetic/adm/123","eligible":true,
             "evaluated_at":"%s","expires_at":"%s","policy_reference":"ADR-BCP-017/PEO-02D"}
            """.formatted(now, now.plusSeconds(3));
    }

    @Test
    void malformedAndSchemaValidForeignOrExpiredFactsDeny() throws Exception {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        var payload = new java.util.concurrent.atomic.AtomicReference<>(decision(now));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Path credential = Files.createTempFile("cp-contract-synthetic-", ".jwt");
        Files.writeString(credential, "synthetic-workload-token-only");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/subscriptions/v1/", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            var gate = new CpInternalAuthorityGate(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credential, HttpClient.newHttpClient(), Clock.fixed(now, ZoneOffset.UTC));
            assertTrue(gate.currentlyAuthorised("tn_synthetic123", "sub_synthetic123", "synthetic/adm/123"));
            for (String bad : java.util.List.of(
                    decision(now).replace("\"eligible\":true", "\"eligible\":false,\"eligible\":true"),
                    decision(now).replace("\"eligible\":true", "\"authority_granted\":true,\"eligible\":true"),
                    decision(now).replace("ADR-BCP-017/PEO-02D", "x".repeat(251)),
                    decision(now).replace("tn_synthetic123", "tn_foreign123"),
                    decision(now).replace(now.plusSeconds(3).toString(), now.minusSeconds(1).toString()),
                    decision(now).replace(now.toString(), "2026-02-30T00:00:00Z"),
                    decision(now).replace(now.toString(), now.plusSeconds(1).toString())
                            .replace(now.plusSeconds(3).toString(), now.plusMillis(500).toString()),
                    decision(now) + " {}")) {
                payload.set(bad);
                assertFalse(gate.currentlyAuthorised("tn_synthetic123", "sub_synthetic123", "synthetic/adm/123"));
            }
            int previous = calls.get();
            assertFalse(gate.currentlyAuthorised("tn_BAD", "sub_synthetic123", "synthetic/adm/123"));
            assertTrue(calls.get() == previous, "invalid tuple must not contact CP");
            assertThrows(IllegalArgumentException.class, () -> new CpInternalAuthorityGate(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), credential,
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(), Clock.systemUTC()));
        } finally {
            server.stop(0);
            Files.deleteIfExists(credential);
        }
    }

    @Test
    void chunkedOversizedRedirectedAndSlowBodiesDenyWithinCompleteDeadline() throws Exception {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        Path credential = Files.createTempFile("cp-body-synthetic-", ".jwt");
        Files.writeString(credential, "synthetic-workload-token-only");
        for (String variant : java.util.List.of("oversized", "redirect", "slow")) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/subscriptions/v1/", exchange -> {
                if (variant.equals("redirect")) exchange.getResponseHeaders().add("Location", "/must-never-follow");
                exchange.sendResponseHeaders(variant.equals("redirect") ? 302 : 200, 0);
                try (var body = exchange.getResponseBody()) {
                    if (variant.equals("slow")) {
                        // Headers and initial body arrive promptly; completion
                        // must still share the original three-second deadline.
                        body.write('{');
                        body.flush();
                        try { Thread.sleep(4000); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    byte[] raw = (variant.equals("oversized") ? "x".repeat(16385) : decision(now))
                            .getBytes(StandardCharsets.UTF_8);
                    body.write(raw);
                } catch (java.io.IOException cancelled) {
                    // Expected cancellation when the client reaches its bound.
                }
            });
            server.start();
            try {
                var gate = new CpInternalAuthorityGate(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        credential, HttpClient.newHttpClient(), Clock.fixed(now, ZoneOffset.UTC));
                long started = System.nanoTime();
                assertFalse(gate.currentlyAuthorised("tn_synthetic123", "sub_synthetic123", "synthetic/adm/123"));
                assertTrue(Duration.ofNanos(System.nanoTime() - started)
                        .toMillis() < 3800, "complete response must be bounded before the slow server finishes");
            } finally {
                server.stop(0);
            }
        }
        Files.deleteIfExists(credential);
    }

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
