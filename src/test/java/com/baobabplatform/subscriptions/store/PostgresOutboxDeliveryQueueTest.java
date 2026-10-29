package com.baobabplatform.subscriptions.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.service.OutboxPublisher;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL 17 tests; never certify delivery to an external broker. */
class PostgresOutboxDeliveryQueueTest {
    private final String tenant = "sub-outbox-test-" + UUID.randomUUID();
    private PostgresBillingStore store;

    private void open() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"),
                "TEST_DATABASE_URL required for PostgreSQL custody checks");
        store = new PostgresBillingStore(url,
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"));
    }

    private void seed(UUID id) {
        store.transact(tx -> {
            tx.appendEvent(new OutboxEvent(id, "billing-subscription.created", tenant,
                    "test-billing-" + id, "{\"id\":\"" + id + "\"}", Instant.now()));
            return null;
        });
    }

    private void expire(UUID id, String status) throws Exception {
        Properties props = new Properties();
        if (System.getenv("TEST_DATABASE_USER") != null)
            props.put("user", System.getenv("TEST_DATABASE_USER"));
        if (System.getenv("TEST_DATABASE_PASSWORD") != null)
            props.put("password", System.getenv("TEST_DATABASE_PASSWORD"));
        try (var connection = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), props);
             var statement = connection.prepareStatement("""
                     UPDATE billing.outbox SET next_attempt_at=now()-INTERVAL '1 second',
                         lease_expires_at=CASE WHEN publication_status='IN_FLIGHT'
                             THEN now()-INTERVAL '1 second' ELSE NULL END
                     WHERE event_id=? AND tenant_id=? AND publication_status=?
                     """)) {
            statement.setObject(1, id);
            statement.setString(2, tenant);
            statement.setString(3, status);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void tenantFenceAndAckRequireOwnerAndCurrentLease() {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            assertTrue(queue.claim("foreign-" + tenant, "worker-a", Duration.ofSeconds(5)).isEmpty());
            var first = queue.claim(tenant, "worker-a", Duration.ofSeconds(5)).orElseThrow();
            assertEquals(id, first.event().eventId());
            assertEquals(1, first.attempt());
            assertTrue(queue.claim(tenant, "worker-b", Duration.ofSeconds(5)).isEmpty());
            var forged = new OutboxDeliveryQueue.Claim(first.event(), "worker-b", first.leaseEpoch(),
                    first.attempt(), first.leaseExpiresAt());
            assertFalse(queue.published(forged));
            var forgedAttempt = new OutboxDeliveryQueue.Claim(first.event(), first.worker(), first.leaseEpoch(),
                    99, first.leaseExpiresAt());
            assertFalse(queue.published(forgedAttempt));
            assertFalse(queue.failed(forgedAttempt, "TRANSPORT_UNCERTAIN", true));
            assertTrue(queue.published(first));
            assertFalse(queue.published(first));
            assertTrue(queue.claim(tenant, "worker-a", Duration.ofSeconds(5)).isEmpty());
            assertEquals(0, queue.health(tenant).pending());
            assertEquals(0, queue.health(tenant).quarantined());
        }
    }

    @Test
    void failedDeliveryPreservesIdentityAndFencesExpiredClaim() throws Exception {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            var first = queue.claim(tenant, "worker-a", Duration.ofSeconds(5)).orElseThrow();
            assertTrue(queue.failed(first, "TRANSPORT_UNCERTAIN", true));
            assertEquals(1, queue.health(tenant).retry());
            assertTrue(queue.claim(tenant, "worker-b", Duration.ofSeconds(5)).isEmpty());
            expire(id, "RETRY");
            var second = queue.claim(tenant, "worker-b", Duration.ofSeconds(5)).orElseThrow();
            assertEquals(id, second.event().eventId());
            assertEquals(2, second.attempt());
            assertFalse(queue.published(first));
            assertFalse(queue.failed(first, "TRANSPORT_UNCERTAIN", true));
            expire(id, "IN_FLIGHT");
            assertFalse(queue.published(second));
            var third = queue.claim(tenant, "worker-c", Duration.ofSeconds(5)).orElseThrow();
            assertEquals(3, third.attempt());
            assertTrue(queue.failed(third, "INVALID_CONTRACT", false));
            assertEquals(1, queue.health(tenant).quarantined());
            assertTrue(queue.claim(tenant, "worker-c", Duration.ofSeconds(5)).isEmpty());
        }
    }

    @Test
    void explicitPublisherOnlyAcksAfterTransportAcknowledgment() {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            var publisher = new OutboxPublisher(queue);
            assertTrue(publisher.runOne(tenant, "worker-a", Duration.ofSeconds(5),
                    event -> OutboxPublisher.Delivery.ACKNOWLEDGED));
            assertTrue(queue.claim(tenant, "worker-a", Duration.ofSeconds(5)).isEmpty());
            assertFalse(publisher.runOne(tenant, "worker-a", Duration.ofSeconds(5),
                    event -> { throw new AssertionError("published events must not deliver twice"); }));
        }
    }

    @Test
    void exhaustionFromCrashedWorkerIsQuarantined() throws Exception {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            for (int i = 0; i < 8; i++) {
                var claim = queue.claim(tenant, "worker-a", Duration.ofSeconds(5)).orElseThrow();
                assertEquals(i + 1, claim.attempt());
                expire(id, "IN_FLIGHT");
            }
            assertTrue(queue.claim(tenant, "worker-b", Duration.ofSeconds(5)).isEmpty());
            assertEquals(1, queue.health(tenant).quarantined());
            assertEquals(8, queue.health(tenant).totalAttempts());
        }
    }

    @Test
    void retryHorizonExpiresAfterSeventyTwoHours() throws Exception {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            queue.claim(tenant, "worker-one", Duration.ofSeconds(10)).orElseThrow();
            Properties props = new Properties();
            if (System.getenv("TEST_DATABASE_USER") != null) props.put("user", System.getenv("TEST_DATABASE_USER"));
            if (System.getenv("TEST_DATABASE_PASSWORD") != null) props.put("password", System.getenv("TEST_DATABASE_PASSWORD"));
            try (var con = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), props);
                 var statement = con.prepareStatement("""
                         UPDATE billing.outbox SET first_attempt_at=now()-INTERVAL '73 hours',
                           lease_expires_at=now()-INTERVAL '1 second'
                         WHERE tenant_id=? AND event_id=?
                         """)) {
                statement.setString(1, tenant);
                statement.setObject(2, id);
                assertEquals(1, statement.executeUpdate());
            }
            assertTrue(queue.claim(tenant, "worker-two", Duration.ofSeconds(10)).isEmpty());
            assertEquals(1L, queue.health(tenant).quarantined());
        }
    }

    @Test
    void replayRequiresCurrentApprovalAndPersistsImmutableEvidence() throws Exception {
        open();
        try (PostgresBillingStore current = store) {
            UUID id = UUID.randomUUID();
            seed(id);
            var queue = current.outboxDeliveryQueue();
            var first = queue.claim(tenant, "worker-one", Duration.ofSeconds(10)).orElseThrow();
            assertTrue(queue.failed(first, "INVALID_CONTRACT", false));
            var request = new com.baobabplatform.subscriptions.service.OutboxReplayService.Request(
                    tenant, id, "OPERATOR_REVIEW", "evidence/replay-123");
            var denied = new com.baobabplatform.subscriptions.service.OutboxReplayService(
                    queue, com.baobabplatform.subscriptions.service.OutboxReplayService.CurrentReplayAuthority.unavailable());
            assertThrows(SecurityException.class, () -> denied.replay(request));
            assertEquals(1L, queue.health(tenant).quarantined());
            var mismatch = new com.baobabplatform.subscriptions.service.OutboxReplayService(queue,
                    req -> java.util.Optional.of(new com.baobabplatform.subscriptions.service.OutboxReplayService.Authorization(
                            "other-tenant", id, "operator-42", "approval/sub-02-123")));
            assertThrows(SecurityException.class, () -> mismatch.replay(request));
            var approved = new com.baobabplatform.subscriptions.service.OutboxReplayService(queue,
                    req -> java.util.Optional.of(new com.baobabplatform.subscriptions.service.OutboxReplayService.Authorization(
                            tenant, id, "operator-42", "approval/sub-02-123")));
            assertTrue(approved.replay(request));
            assertFalse(approved.replay(request));
            var replayed = queue.claim(tenant, "worker-two", Duration.ofSeconds(10)).orElseThrow();
            assertEquals(id, replayed.event().eventId());
            assertEquals(com.baobabplatform.subscriptions.json.Json.mapper()
                    .readTree(first.event().envelopeJson()),
                    com.baobabplatform.subscriptions.json.Json.mapper()
                    .readTree(replayed.event().envelopeJson()));
            assertEquals(1, replayed.attempt());
            Properties props = new Properties();
            if (System.getenv("TEST_DATABASE_USER") != null) props.put("user", System.getenv("TEST_DATABASE_USER"));
            if (System.getenv("TEST_DATABASE_PASSWORD") != null) props.put("password", System.getenv("TEST_DATABASE_PASSWORD"));
            try (var con = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), props);
                 var query = con.prepareStatement("""
                         SELECT principal_id, approval_reference, reason_code, evidence_reference
                           FROM billing.outbox_replay_audit WHERE event_id=? AND tenant_id=?
                         """)) {
                query.setObject(1, id);
                query.setString(2, tenant);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("operator-42", rs.getString(1));
                    assertEquals("approval/sub-02-123", rs.getString(2));
                    assertEquals("OPERATOR_REVIEW", rs.getString(3));
                    assertEquals("evidence/replay-123", rs.getString(4));
                    assertFalse(rs.next());
                }
            }
        }
    }

}
