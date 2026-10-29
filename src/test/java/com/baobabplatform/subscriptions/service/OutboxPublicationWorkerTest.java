package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OutboxPublicationWorkerTest {
    private static final class Queue implements OutboxDeliveryQueue {
        final List<String> visited = new ArrayList<>();
        @Override public Optional<Claim> claim(String tenantId, String worker, Duration lease) {
            visited.add(tenantId);
            return Optional.empty();
        }
        @Override public boolean published(Claim claim) { return false; }
        @Override public boolean failed(Claim claim, String code, boolean retryable) { return false; }
        @Override public boolean replay(String tenant, UUID event, String principal, String approval, String reason) {
            return false;
        }
        @Override public Health health(String tenant) {
            return new Health(1, 0, 0, 0, 0, 1);
        }
    }

    @Test
    void failClosedAuthorityAndFairBoundedTenants() {
        Queue queue = new Queue();
        AtomicBoolean allowed = new AtomicBoolean(false);
        Set<String> tenants = Set.of("tenant-1","tenant-2","tenant-3","tenant-4",
                "tenant-5","tenant-6","tenant-7","tenant-8","tenant-9");
        try (var worker = new OutboxPublicationWorker(queue,
                event -> OutboxPublisher.Delivery.ACKNOWLEDGED,
                () -> { if (!allowed.get()) throw new SecurityException("revoked"); },
                tenants, "publisher-worker", Duration.ofSeconds(10))) {
            assertThrows(SecurityException.class, worker::runOnce);
            assertTrue(queue.visited.isEmpty());
            allowed.set(true);
            var first = worker.runOnce();
            assertEquals(8, first.tenantsChecked());
            assertEquals(8, queue.visited.size());
            assertEquals(8L, first.pending());
            var second = worker.runOnce();
            assertEquals(16, queue.visited.size());
            assertEquals(8, second.tenantsChecked());
            assertNotEquals(queue.visited.get(0), queue.visited.get(8));
            assertEquals(9, Set.copyOf(queue.visited).size());
            allowed.set(false);
            assertThrows(SecurityException.class, worker::runOnce);
            assertEquals(16, queue.visited.size());
        }
    }

    @Test
    void invalidWorkerScopeAndTenantCeilingRefused() {
        Queue queue = new Queue();
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxPublicationWorker(queue,
                        event -> OutboxPublisher.Delivery.RETRYABLE_FAILURE,
                        () -> {}, Set.of("tenant-1"), "x", Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxPublicationWorker(queue,
                        event -> OutboxPublisher.Delivery.RETRYABLE_FAILURE,
                        () -> {}, Set.of("tenant-1"), "publisher-1", Duration.ofSeconds(1)));
    }
}
