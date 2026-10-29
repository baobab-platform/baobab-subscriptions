package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import java.time.Duration;
import java.util.Objects;

/**
 * Transport-neutral outbox publisher; never promotes a transport attempt to
 * an ACK. A lost lease is not an accepted publication, and a failed local
 * database write cannot be misclassified as a remote transport failure.
 */
public final class OutboxPublisher {
    public enum Delivery { ACKNOWLEDGED, RETRYABLE_FAILURE, PERMANENT_FAILURE }
    public enum Outcome { IDLE, ACKNOWLEDGED, RETRY_SCHEDULED, QUARANTINED, LEASE_LOST }

    @FunctionalInterface
    public interface Transport {
        /** May return ACKNOWLEDGED only for this event's durable receiver receipt. */
        Delivery deliver(OutboxEvent event) throws Exception;
    }

    private final OutboxDeliveryQueue queue;

    public OutboxPublisher(OutboxDeliveryQueue queue) {
        this.queue = Objects.requireNonNull(queue);
    }

    /** Compatibility API: an attempted, even lease-lost, delivery is not idle. */
    public boolean runOne(String tenantId, String worker, Duration lease, Transport transport) {
        return runOneObserved(tenantId, worker, lease, transport) != Outcome.IDLE;
    }

    /** Precisely distinguish durable ACK, quarantine, retry, and stale custody. */
    public Outcome runOneObserved(String tenantId, String worker, Duration lease, Transport transport) {
        Objects.requireNonNull(transport);
        var candidate = queue.claim(tenantId, worker, lease);
        if (candidate.isEmpty()) return Outcome.IDLE;
        var claim = candidate.orElseThrow();
        Delivery delivered;
        boolean uncertain = false;
        try {
            delivered = transport.deliver(claim.event());
            if (delivered == null) {
                uncertain = true;
                delivered = Delivery.RETRYABLE_FAILURE;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            uncertain = true;
            delivered = Delivery.RETRYABLE_FAILURE;
        } catch (Exception transportFailure) {
            // Remote acceptance may have happened even when the local caller
            // timed out or lost the response. Retry the ORIGINAL event ID.
            uncertain = true;
            delivered = Delivery.RETRYABLE_FAILURE;
        }

        // Database failures deliberately propagate: never catch and rewrite
        // them as transport errors. State transitions are lease-epoch fenced.
        return switch (delivered) {
            case ACKNOWLEDGED -> queue.published(claim) ? Outcome.ACKNOWLEDGED : Outcome.LEASE_LOST;
            case RETRYABLE_FAILURE -> queue.failed(claim,
                    uncertain ? "TRANSPORT_UNCERTAIN" : "TRANSPORT_RETRYABLE", true)
                    ? Outcome.RETRY_SCHEDULED : Outcome.LEASE_LOST;
            case PERMANENT_FAILURE -> queue.failed(claim, "TRANSPORT_PERMANENT", false)
                    ? Outcome.QUARANTINED : Outcome.LEASE_LOST;
        };
    }
}
