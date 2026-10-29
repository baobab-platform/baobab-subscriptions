package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import java.time.Duration;
import java.util.Objects;

/**
 * Transport-neutral, explicitly invoked publisher. No production transport is
 * configured by this class. An acknowledgement, not an HTTP attempt or queue
 * lease, is required before marking a record published (ADR-SUB-0014 §88).
 */
public final class OutboxPublisher {
    public enum Delivery { ACKNOWLEDGED, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    @FunctionalInterface
    public interface Transport {
        /** Must return ACKNOWLEDGED only for the configured transport's durable ACK. */
        Delivery deliver(OutboxEvent event) throws Exception;
    }

    private final OutboxDeliveryQueue queue;

    public OutboxPublisher(OutboxDeliveryQueue queue) {
        this.queue = Objects.requireNonNull(queue);
    }

    /** Returns false when no due event exists; never sends money or mutates CP. */
    public boolean runOne(String tenantId, String worker, Duration lease, Transport transport) {
        Objects.requireNonNull(transport);
        var candidate = queue.claim(tenantId, worker, lease);
        if (candidate.isEmpty()) return false;
        var claim = candidate.orElseThrow();
        try {
            Delivery delivery = Objects.requireNonNull(transport.deliver(claim.event()));
            switch (delivery) {
                case ACKNOWLEDGED -> queue.published(claim);
                case RETRYABLE_FAILURE -> queue.failed(claim, "TRANSPORT_RETRYABLE", true);
                case PERMANENT_FAILURE -> queue.failed(claim, "TRANSPORT_PERMANENT", false);
            }
        } catch (Exception unexpected) {
            // Ambiguous transport outcome may already be accepted remotely.
            // Preserve the event identity and retry only after the lease fence.
            queue.failed(claim, "TRANSPORT_UNCERTAIN", true);
        }
        return true;
    }
}
