package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.KillBillAdapter;
import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 17 verifies durable observation queue, bounded retry exhaustion
 * and tenant-specific monitoring without contacting Kill Bill.
 */
class ProviderWorkQueuePostgresTest {
    @Test
    void retryScheduleAndExhaustionSurvivePoolRestart() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"),
                "PostgreSQL 17 integration fixture required");
        String tenant = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
        var endpoint = new KillBillAdapter.TenantEndpoint(
                java.net.URI.create("https://killbill.invalid"),
                Path.of("/nonexistent/username"), Path.of("/nonexistent/password"),
                Path.of("/nonexistent/api-key"), Path.of("/nonexistent/api-secret"));
        UUID operationId;
        try (KillBillAdapter adapter = new KillBillAdapter(Map.of(tenant, endpoint));
             PostgresBillingStore pg = new PostgresBillingStore(url,
                     System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"))) {
            BillingService service = new BillingService(pg, adapter, PaymentsPort.notConfigured(),
                    BillingPolicies.load(), Clock.systemUTC(), (t,s,r) -> true);
            String body = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                    .replace(Fixtures.ACME_TENANT, tenant);
            String key = "retry-test-" + UUID.randomUUID();
            service.ensure(new CallContext("baobab-cp-workload", "synthetic-operator",
                    key, UUID.randomUUID().toString()), body.getBytes(StandardCharsets.UTF_8));
            ProviderOperation op = pg.transact(tx -> tx.providerOperationForKey(
                    tenant, "KILL_BILL", "ENSURE_SUBSCRIPTION", key)).orElseThrow();
            operationId = op.operationId();
            assertTrue(pg.preparedProviderOperations(tenant, 10).stream()
                    .anyMatch(item -> item.operationId().equals(operationId)));
            assertTrue(pg.preparedProviderOperations("tn_unrelatedtenant", 10).isEmpty());
            service.providerOperations().claimForDispatch(tenant, operationId, "synthetic-worker",
                    Duration.ofSeconds(1));
            Instant afterLease = Instant.now().plusSeconds(5);
            assertTrue(pg.dueProviderObservations(tenant, 25, afterLease).stream()
                    .anyMatch(item -> item.operationId().equals(operationId)));
            pg.recordReconciliationAttempt(tenant, operationId, false, "PROVIDER_TIMEOUT", afterLease);
            assertFalse(pg.dueProviderObservations(tenant, 25, afterLease).stream()
                    .anyMatch(item -> item.operationId().equals(operationId)));
            for (int i = 1; i < 8; i++) {
                pg.recordReconciliationAttempt(tenant, operationId,
                        false, "PROVIDER_TIMEOUT", afterLease.plusSeconds(i));
            }
            assertEquals(1L, pg.reconciliationCounts(tenant).get("EXHAUSTED").longValue());
            assertEquals(0L, pg.reconciliationCounts("tn_unrelatedtenant")
                    .getOrDefault("EXHAUSTED", 0L).longValue());
        }
        try (PostgresBillingStore restarted = new PostgresBillingStore(url,
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"))) {
            assertEquals(1L, restarted.reconciliationCounts(tenant).get("EXHAUSTED").longValue());
            assertFalse(restarted.dueProviderObservations(tenant, 25,
                            Instant.now().plus(Duration.ofDays(30))).stream()
                    .anyMatch(item -> item.operationId().equals(operationId)),
                    "exhausted uncertainty may not resume automatically after restart");
        }
    }
}
