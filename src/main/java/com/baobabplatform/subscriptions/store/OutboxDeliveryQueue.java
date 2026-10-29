package com.baobabplatform.subscriptions.store;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Tenant-scoped durable publication custody, not an event transport.
 * Only an acknowledged delivery may call published(); failures remain
 * replayable with the original immutable event ID (ADR-SUB-0014).
 */
public interface OutboxDeliveryQueue {
    record Claim(OutboxEvent event, String worker, long leaseEpoch, int attempt,
                 Instant leaseExpiresAt) { }

    record Health(long pending, long inFlight, long retry, long quarantined,
                  long totalAttempts, long oldestUnpublishedSeconds) { }

    /** One due event; concurrent workers must not claim the same live lease. */
    Optional<Claim> claim(String tenantId, String worker, Duration lease);

    /** True only if this still-valid lease owns the event. Requires transport ACK. */
    boolean published(Claim claim);

    /** Retryable failures back off; permanent failures and exhaustion quarantine. */
    boolean failed(Claim claim, String safeFailureCode, boolean retryable);

    /** Diagnostic counts; tenant IDs are never exported as metric labels. */
    Health health(String tenantId);
}
