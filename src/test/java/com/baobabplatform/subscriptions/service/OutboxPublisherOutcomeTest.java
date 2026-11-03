package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxPublisherOutcomeTest {
    private static final class Queue implements OutboxDeliveryQueue {
        final OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "event",
                "tn_test01", "bsub_test01", "{}", Instant.now());
        Claim next = new Claim(event, "publisher-one", 4, 1, Instant.now().plusSeconds(30));
        boolean accepted = true;
        String failureCode;
        boolean retryable;
        boolean databaseUnavailable;

        @Override public Optional<Claim> claim(String tenant, String worker, Duration lease) {
            return Optional.ofNullable(next);
        }
        @Override public boolean published(Claim claim) {
            if (databaseUnavailable) throw new IllegalStateException("database unavailable");
            return accepted;
        }
        @Override public boolean failed(Claim claim, String code, boolean retry) {
            failureCode = code;
            retryable = retry;
            if (databaseUnavailable) throw new IllegalStateException("database unavailable");
            return accepted;
        }
        @Override public Health health(String tenant) {
            return new Health(1, 0, 0, 0, 0, 0);
        }
        @Override public boolean replay(String tenant, UUID eventId, String principal,
                String approval, String reason, String evidence) {
            return false;
        }
    }

    private static OutboxPublisher.Outcome send(Queue queue, OutboxPublisher.Transport transport) {
        return new OutboxPublisher(queue).runOneObserved("tn_test01", "publisher-one",
                Duration.ofSeconds(30), transport);
    }

    @Test
    void ackMustBePersistedAndNotConfusedWithLostLease() {
        Queue queue = new Queue();
        assertEquals(OutboxPublisher.Outcome.ACKNOWLEDGED,
                send(queue, event -> OutboxPublisher.Delivery.ACKNOWLEDGED));
        queue.accepted = false;
        assertEquals(OutboxPublisher.Outcome.LEASE_LOST,
                send(queue, event -> OutboxPublisher.Delivery.ACKNOWLEDGED));
        queue.databaseUnavailable = true;
        assertThrows(IllegalStateException.class,
                () -> send(queue, event -> OutboxPublisher.Delivery.ACKNOWLEDGED));
    }

    @Test
    void ambiguousRemoteAckMustRetryOriginalIdentity() {
        Queue queue = new Queue();
        UUID expected = queue.event.eventId();
        assertEquals(OutboxPublisher.Outcome.RETRY_SCHEDULED, send(queue, event -> {
            assertEquals(expected, event.eventId());
            throw new IOException("response lost after consumer received event");
        }));
        assertEquals("TRANSPORT_UNCERTAIN", queue.failureCode);
        assertTrue(queue.retryable);
        assertEquals(OutboxPublisher.Outcome.RETRY_SCHEDULED, send(queue, event -> null));
        assertEquals("TRANSPORT_UNCERTAIN", queue.failureCode);
        queue.next = new OutboxDeliveryQueue.Claim(queue.event, "publisher-one", 5,
                OutboxDeliveryQueue.MAX_ATTEMPTS, Instant.now().plusSeconds(30));
        assertEquals(OutboxPublisher.Outcome.QUARANTINED,
                send(queue, event -> OutboxPublisher.Delivery.RETRYABLE_FAILURE));
        queue.accepted = false;
        assertEquals(OutboxPublisher.Outcome.LEASE_LOST,
                send(queue, event -> OutboxPublisher.Delivery.RETRYABLE_FAILURE));
    }

    @Test
    void permanentFailureQuarantinesAndIdleSkipsTransport() {
        Queue queue = new Queue();
        assertEquals(OutboxPublisher.Outcome.QUARANTINED,
                send(queue, event -> OutboxPublisher.Delivery.PERMANENT_FAILURE));
        assertEquals("TRANSPORT_PERMANENT", queue.failureCode);
        assertFalse(queue.retryable);
        queue.next = null;
        assertEquals(OutboxPublisher.Outcome.IDLE, send(queue,
                event -> { throw new AssertionError("idle must not deliver"); }));
    }
}
