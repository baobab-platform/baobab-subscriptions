package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.fasterxml.jackson.core.JsonParser;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared events/v1 signed HTTPS delivery binding (FB-04); not an authorised
 * Subscriptions-to-CP route. No instance is wired into Application until
 * Shared explicitly accepts a Subscriptions consumer and its IAM publisher.
 *
 * <p>The sender never treats an arbitrary 2xx as a durable ACK. The recipient
 * must return the closed v1 receipt for the exact canonical event ID.
 */
public final class SignedHttpsEventTransport implements OutboxPublisher.Transport {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final Set<Integer> PERMANENT = Set.of(400, 409, 413, 422);
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final Map<String, String> PAYLOAD_TYPES = Map.of(
            "com.baobab-platform.subscriptions.billing-subscription.created.v1", "BillingSubscriptionCreated",
            "com.baobab-platform.subscriptions.billing-subscription.resumed.v1", "BillingSubscriptionResumed",
            "com.baobab-platform.subscriptions.billing-subscription.suspended.v1", "BillingSubscriptionSuspended",
            "com.baobab-platform.subscriptions.billing-subscription.terminated.v1", "BillingSubscriptionTerminated",
            "com.baobab-platform.subscriptions.usage.recorded.v1", "UsageRecorded");
    private final URI endpoint;
    private final String recipient;
    private final String keyId;
    private final Path secretFile;
    private final HttpClient http;
    private final Clock clock;

    public SignedHttpsEventTransport(URI endpoint, String recipient, String keyId,
            Path secretFile, HttpClient http, Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.recipient = Objects.requireNonNull(recipient);
        this.keyId = Objects.requireNonNull(keyId);
        this.secretFile = Objects.requireNonNull(secretFile);
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        boolean loopback = endpoint.getHost() != null && Set.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost());
        if ((! "https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme()) && loopback))
                || endpoint.getPort() == 0 || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !"/integration/events".equals(endpoint.getRawPath())
                || !"baobab-control-plane".equals(recipient)
                || !keyId.matches("[a-z0-9][a-z0-9._-]{2,63}")
                || !secretFile.isAbsolute() || http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("unqualified signed event delivery endpoint or key");
        }
    }

    public static SignedHttpsEventTransport create(URI endpoint, String recipient,
            String keyId, Path secretFile) {
        return new SignedHttpsEventTransport(endpoint, recipient, keyId, secretFile,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    private byte[] secret() throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(secretFile)) {
            bytes = input.readNBytes(4097);
        }
        if (bytes.length < 32 || bytes.length > 4096) {
            throw new IOException("invalid signed delivery secret length");
        }
        return bytes;
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String signature(byte[] payload, String stamp) throws Exception {
        String text = "baobab-event-delivery-v1\n" + recipient + "\n" + keyId
                + "\n" + stamp + "\n" + digest(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] key = secret();
        try {
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "hmac-sha256=" + HexFormat.of().formatHex(
                    mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean matchesEnvelope(OutboxEvent event) {
        try {
            // Duplicate JSON keys can cause a consumer to see a different
            // event than the sender validated. Reject them before signing.
            JsonNode json = Json.mapper().reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(event.envelopeJson());
            String definition = PAYLOAD_TYPES.get(event.eventType());
            if (json == null || !json.isObject() || definition == null
                    || !event.eventId().toString().equals(json.path("id").asText())
                    || !"urn:baobab-platform:service:baobab-subscriptions".equals(json.path("source").asText())
                    || !event.eventType().equals(json.path("type").asText())
                    || !event.tenantId().equals(json.path("tenantid").asText())
                    || !"tenant".equals(json.path("baobabscope").asText())
                    || !("billing-subscription:" + event.aggregateId()).equals(json.path("subject").asText())
                    || !(Contracts.BASE + Contracts.def(Contracts.EVENTS, definition))
                            .equals(json.path("dataschema").asText())
                    || !java.time.Instant.parse(json.path("time").asText())
                            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                            .equals(event.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
                return false;
            }
            return Contracts.problems(Contracts.ENVELOPE, json).isEmpty()
                    && Contracts.problems(Contracts.def(Contracts.EVENTS, definition),
                            json.path("data")).isEmpty();
        } catch (RuntimeException | IOException invalid) {
            return false;
        }
    }

    @Override
    public OutboxPublisher.Delivery deliver(OutboxEvent event) {
        Objects.requireNonNull(event);
        if (!matchesEnvelope(event)) return OutboxPublisher.Delivery.PERMANENT_FAILURE;
        byte[] body = event.envelopeJson().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_EVENT_BYTES) return OutboxPublisher.Delivery.PERMANENT_FAILURE;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            String timestamp = STAMP.format(clock.instant());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/cloudevents+json")
                    .header("Accept", "application/json")
                    .header("Baobab-Key-Id", keyId)
                    .header("Baobab-Timestamp", timestamp)
                    .header("Baobab-Signature", signature(body, timestamp))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = http.sendAsync(request, ignored -> new BoundedJsonBodySubscriber());
            HttpResponse<byte[]> response = pending.get(3, TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status != 200 && status != 202) {
                return PERMANENT.contains(status)
                        ? OutboxPublisher.Delivery.PERMANENT_FAILURE
                        : OutboxPublisher.Delivery.RETRYABLE_FAILURE;
            }
            JsonNode receipt = Json.mapper().readTree(response.body());
            if (receipt == null || !receipt.isObject() || receipt.size() != 3
                    || !receipt.has("event_id") || !receipt.has("status") || !receipt.has("received_at")
                    || !receipt.path("event_id").isTextual()
                    || !event.eventId().equals(UUID.fromString(receipt.path("event_id").asText()))
                    || !receipt.path("received_at").isTextual()
                    || java.time.Instant.parse(receipt.path("received_at").asText()).isAfter(
                            clock.instant().plusSeconds(300)) ||
                    java.time.Instant.parse(receipt.path("received_at").asText()).isBefore(
                            clock.instant().minusSeconds(300))
                    || !((status == 202 && "ACCEPTED".equals(receipt.path("status").asText()))
                            || (status == 200 && "DUPLICATE".equals(receipt.path("status").asText())))) {
                return OutboxPublisher.Delivery.RETRYABLE_FAILURE;
            }
            return OutboxPublisher.Delivery.ACKNOWLEDGED;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return OutboxPublisher.Delivery.RETRYABLE_FAILURE;
        } catch (Exception invalid) {
            // Includes malformed receipts, unavailable/revoked key file,
            // timeouts and uncertain transport acceptance. Never mark ACK.
            return OutboxPublisher.Delivery.RETRYABLE_FAILURE;
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }
}
