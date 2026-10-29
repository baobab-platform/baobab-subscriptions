package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baobabplatform.subscriptions.store.OutboxEvent;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local synthetic HTTPS-binding fixture; NOT a qualified CP subscription receiver. */
class SignedHttpsEventTransportTest {
    @TempDir Path dir;
    private static final Instant TIME = Instant.parse("2026-10-29T10:00:00Z");
    private static final String TYPE = "com.baobab-platform.subscriptions.billing-subscription.created.v1";

    private OutboxEvent event() {
        UUID id = UUID.randomUUID();
        var envelope = Json.mapper().createObjectNode()
                .put("specversion", "1.0")
                .put("id", id.toString())
                .put("type", TYPE)
                .put("source", "urn:baobab-platform:service:baobab-subscriptions")
                .put("subject", "billing-subscription:bsub_test01")
                .put("time", TIME.toString())
                .put("datacontenttype", "application/json")
                .put("dataschema", Contracts.BASE
                        + Contracts.def(Contracts.EVENTS, "BillingSubscriptionCreated"))
                .put("baobabscope", "tenant")
                .put("correlationid", UUID.randomUUID().toString())
                .put("tenantid", "tn_test01");
        var data = Json.mapper().createObjectNode()
                .put("billing_subscription_id", "bsub_test01")
                .put("product_subscription_id", "sub_test01")
                .put("tenant_id", "tn_test01")
                .put("authoritative_revision", 1)
                .put("subscription_type", "INTERNAL")
                .put("billing_required", false)
                .put("billing_state", "ACTIVE")
                .put("simulated", true)
                .put("created_at", TIME.toString());
        envelope.set("data", data);
        return new OutboxEvent(id, TYPE, "tn_test01", "bsub_test01", envelope.toString(), TIME);
    }

    private static String sign(String recipient, String key, String stamp, byte[] body, byte[] secret) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return "hmac-sha256=" + HexFormat.of().formatHex(hmac.doFinal(
                ("baobab-event-delivery-v1\n" + recipient + "\n" + key + "\n" + stamp + "\n" + digest)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void exactSignedDeliveryAndReceiptIdRequired() throws Exception {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 0x5A);
        Path keyFile = dir.resolve("event-secret");
        Files.write(keyFile, secret);
        OutboxEvent event = event();
        AtomicReference<String> signed = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/integration/events", exchange -> {
            calls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String stamp = exchange.getRequestHeaders().getFirst("Baobab-Timestamp");
            String sig = exchange.getRequestHeaders().getFirst("Baobab-Signature");
            try {
                if (sign("baobab-control-plane", "sub-sign-2026", stamp, body, secret).equals(sig)
                        && "application/cloudevents+json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                        && event.envelopeJson().equals(new String(body, StandardCharsets.UTF_8))) {
                    signed.set(sig);
                }
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            int status = calls.get() == 1 ? 202 : 200;
            String receipt = "{\"event_id\":\"" + event.eventId() + "\",\"status\":\""
                    + (status == 202 ? "ACCEPTED" : "DUPLICATE")
                    + "\",\"received_at\":\"" + TIME + "\"}";
            byte[] response = receipt.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        fake.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/integration/events");
            var transport = new SignedHttpsEventTransport(uri, "baobab-control-plane", "sub-sign-2026",
                    keyFile, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                    Clock.fixed(TIME, ZoneOffset.UTC));
            assertEquals(OutboxPublisher.Delivery.ACKNOWLEDGED, transport.deliver(event));
            assertEquals(OutboxPublisher.Delivery.ACKNOWLEDGED, transport.deliver(event));
            assertNotNull(signed.get());
            assertEquals(2, calls.get());
            var foreign = new OutboxEvent(event.eventId(), TYPE, "foreign", event.aggregateId(),
                    event.envelopeJson(), TIME);
            assertEquals(OutboxPublisher.Delivery.PERMANENT_FAILURE, transport.deliver(foreign));
            assertEquals(2, calls.get());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void rejectsForgedAckAndUnapprovedHost() throws Exception {
        Path keyFile = dir.resolve("event-secret");
        byte[] fixtureKey = new byte[32];
        java.util.Arrays.fill(fixtureKey, (byte) 0x5A);
        Files.write(keyFile, fixtureKey);
        OutboxEvent event = event();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/integration/events", exchange -> {
            String invalid = "{\"event_id\":\"" + UUID.randomUUID()
                    + "\",\"status\":\"ACCEPTED\",\"received_at\":\"" + TIME + "\"}";
            byte[] body = invalid.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        fake.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/integration/events");
            var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var transport = new SignedHttpsEventTransport(uri,"baobab-control-plane","sub-sign-2026",
                    keyFile,client,Clock.fixed(TIME,ZoneOffset.UTC));
            assertEquals(OutboxPublisher.Delivery.RETRYABLE_FAILURE, transport.deliver(event));
            assertThrows(IllegalArgumentException.class,
                    () -> new SignedHttpsEventTransport(URI.create("http://example.com/integration/events"),
                            "baobab-control-plane","sub-sign-2026",keyFile,client,Clock.systemUTC()));
            assertThrows(IllegalArgumentException.class,
                    () -> new SignedHttpsEventTransport(uri,"baobab-payments",
                            "sub-sign-2026",keyFile,client,Clock.systemUTC()));
        } finally { fake.stop(0); }
    }
}
