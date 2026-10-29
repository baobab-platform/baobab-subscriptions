package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.auth.CurrentTokenStatus;
import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.log.Log;
import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-SUB-0014: bounded, tenant-fair outbox publisher. This component is NOT
 * automatically started; Application has no approved Subscriptions ingress.
 *
 * <p>The verifier's audience is event-broker, never baobab-subscriptions.
 * Current issuer status is required per tick, with no positive cache.
 */
public final class OutboxPublicationWorker implements AutoCloseable {
    @FunctionalInterface
    public interface PublisherAuthority {
        /** Throws/denies whenever IAM issuance or current status is not valid. */
        void requireCurrent();
    }

    public static PublisherAuthority iam(java.net.URI issuer, java.net.URI jwksUri,
            CurrentTokenStatus status, Path bearerFile, String clientId) {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(jwksUri);
        Objects.requireNonNull(status);
        if (bearerFile == null || !bearerFile.isAbsolute()
                || clientId == null || !clientId.matches("[A-Za-z0-9._:-]{3,128}")) {
            throw new IllegalArgumentException("current event-broker publisher identity required");
        }
        // Enforce the Shared scope's fixed resource-server audience rather
        // than accepting a caller-supplied authenticator for another engine.
        WorkloadAuthenticator eventBrokerAuthenticator = WorkloadAuthenticator.remote(
                issuer, jwksUri, "event-broker", Set.of(clientId)).withCurrentStatus(status);
        return () -> {
            byte[] bytes;
            try (var in = Files.newInputStream(bearerFile)) {
                bytes = in.readNBytes(8193);
            } catch (IOException missing) {
                throw new IllegalStateException("publisher IAM credential unavailable", missing);
            }
            if (bytes.length == 0 || bytes.length > 8192) {
                throw new IllegalStateException("publisher IAM credential invalid");
            }
            String token;
            try {
                token = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().trim();
            } catch (java.nio.charset.CharacterCodingException malformed) {
                throw new IllegalStateException("publisher IAM credential invalid", malformed);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            if (token.isEmpty() || token.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw new IllegalStateException("publisher IAM credential invalid");
            }
            var verified = eventBrokerAuthenticator.authenticate("Bearer " + token, "events:publish");
            if (!clientId.equals(verified.clientId()) || verified.subject() == null
                    || verified.subject().isBlank()) {
                throw new IllegalStateException("publisher IAM client mismatch");
            }
        };
    }

    public record Tick(int attempted, int acknowledged, int retryScheduled, int quarantinedNow,
            int lostLease, int tenantsChecked, long pending, long inFlight,
            long retry, long quarantined, long totalAttempts, long oldestUnpublishedSeconds) { }

    private final OutboxPublisher publisher;
    private final OutboxDeliveryQueue queue;
    private final PublisherAuthority authority;
    private final OutboxPublisher.Transport transport;
    private final List<String> tenants;
    private final String workerId;
    private final Duration lease;
    private final AtomicInteger cursor = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("billing-outbox-publisher").daemon(true).unstarted(r));

    public OutboxPublicationWorker(OutboxDeliveryQueue queue, OutboxPublisher.Transport transport,
            PublisherAuthority authority, Set<String> tenants, String workerId, Duration lease) {
        this.queue = Objects.requireNonNull(queue);
        this.publisher = new OutboxPublisher(queue);
        this.transport = Objects.requireNonNull(transport);
        this.authority = Objects.requireNonNull(authority);
        this.workerId = Objects.requireNonNull(workerId);
        this.lease = Objects.requireNonNull(lease);
        if (!workerId.matches("[A-Za-z0-9._:-]{3,128}") || tenants == null || tenants.isEmpty()
                || tenants.size() > 32 || tenants.stream().anyMatch(t -> t == null || t.isBlank())
                || lease.compareTo(Duration.ofSeconds(10)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("publisher requires approved bounded tenant set and lease");
        }
        List<String> ordered = new ArrayList<>(tenants);
        Collections.sort(ordered);
        this.tenants = List.copyOf(ordered);
    }

    public void start(Duration interval) {
        if (interval == null || interval.compareTo(Duration.ofSeconds(30)) < 0
                || interval.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("publisher interval must be 30 seconds to 30 minutes");
        }
        scheduler.scheduleWithFixedDelay(this::safeTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            Tick t = runOnce();
            // No unbounded tenant/event/worker identifiers or payloads in metrics/logging.
            Log.info("billing.outbox.publisher.tick", Map.of(
                    "attempted", t.attempted(), "acknowledged", t.acknowledged(),
                    "retry_scheduled", t.retryScheduled(), "quarantined_now", t.quarantinedNow(),
                    "lost_lease", t.lostLease(), "tenants_checked", t.tenantsChecked(),
                    "pending", t.pending(), "in_flight", t.inFlight(),
                    "retry", t.retry(), "quarantined", t.quarantined(),
                    "oldest_unpublished_seconds", t.oldestUnpublishedSeconds()));
        } catch (RuntimeException rejected) {
            Log.warn("billing.outbox.publisher.blocked",
                    Map.of("reason", rejected.getClass().getSimpleName()));
        }
    }

    /** One per tenant, at most 8 tenants per tick, round-robin across ticks. */
    public Tick runOnce() {
        authority.requireCurrent();
        int count = Math.min(8, tenants.size());
        int start = Math.floorMod(cursor.getAndUpdate(n -> (n + count) % tenants.size()), tenants.size());
        int sent = 0, acknowledged = 0, retryScheduled = 0, quarantinedNow = 0, lostLease = 0;
        long pending = 0, inFlight = 0, retry = 0, quarantined = 0, attempts = 0, oldest = 0;
        for (int i = 0; i < count; i++) {
            String tenant = tenants.get((start + i) % tenants.size());
            OutboxPublisher.Outcome outcome = publisher.runOneObserved(tenant, workerId, lease, transport);
            switch (outcome) {
                case IDLE -> { }
                case ACKNOWLEDGED -> { sent++; acknowledged++; }
                case RETRY_SCHEDULED -> { sent++; retryScheduled++; }
                case QUARANTINED -> { sent++; quarantinedNow++; }
                case LEASE_LOST -> { sent++; lostLease++; }
            }
            var h = queue.health(tenant);
            pending += h.pending();
            inFlight += h.inFlight();
            retry += h.retry();
            quarantined += h.quarantined();
            attempts += h.totalAttempts();
            oldest = Math.max(oldest, h.oldestUnpublishedSeconds());
        }
        return new Tick(sent, acknowledged, retryScheduled, quarantinedNow, lostLease,
                count, pending, inFlight, retry, quarantined, attempts, oldest);
    }

    @Override public void close() {
        scheduler.shutdownNow();
    }
}
