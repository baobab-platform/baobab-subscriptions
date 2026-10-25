package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.provider.ProviderCapabilities;
import com.baobabplatform.subscriptions.provider.ProviderOperationIntent;
import com.baobabplatform.subscriptions.provider.ProviderOutcome;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One identical set of provider-custody tests in memory and in PostgreSQL 17.
 * Only a fictional, non-monetary observer is used: there is no Kill Bill IO.
 */
class ProviderReconciliationTest {
    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-10-25T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
        void advance(Duration duration) { current = current.plus(duration); }
    }

    private static final class FakeObserver implements BillingProvider {
        final AtomicInteger observations = new AtomicInteger();
        boolean throwOnObserve;
        boolean returnUnknown;

        @Override public String kind() { return "KILLBILL"; }
        @Override public boolean simulated() { return false; }
        @Override public String ensureSubscription(Projection projection) {
            throw new AssertionError("provider dispatch must never run inside the billing transaction");
        }
        @Override public void suspend(Projection projection) {
            throw new AssertionError("unqualified suspension is forbidden");
        }
        @Override public void cancel(Projection projection) {
            throw new AssertionError("unqualified cancellation is forbidden");
        }
        @Override public void recordUsage(Projection projection, UsageRecord usage) {
            throw new AssertionError("unqualified usage dispatch is forbidden");
        }
        @Override public boolean healthy() { return true; }
        @Override public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(false, false, false, false, false, false, false, true);
        }
        @Override
        public ProviderOutcome observeSubscription(Projection projection, ProviderOperationIntent intent) {
            observations.incrementAndGet();
            if (throwOnObserve) {
                throw new IllegalStateException("fictional network outage");
            }
            if (returnUnknown) {
                return ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_NOT_CONVERGED");
            }
            return ProviderOutcome.alreadyConverged("native-" + intent.operationId());
        }
    }

    @Test
    void inMemoryProviderCustodyAndCrashRecovery() {
        try (BillingStore store = new InMemoryBillingStore()) {
            exercise(store);
        }
    }

    @Test
    void postgres17ProviderCustodyAndCrashRecovery() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"), "PostgreSQL integration credentials unavailable");
        try (BillingStore store = new PostgresBillingStore(url,
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"))) {
            exercise(store);
        }
    }

    private static void exercise(BillingStore store) {
        MutableClock clock = new MutableClock();
        FakeObserver observer = new FakeObserver();
        BillingService billing = new BillingService(store, observer, PaymentsPort.notConfigured(),
                BillingPolicies.load(), clock, (tenant, product, reference) -> true);
        String tenant = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
        String body = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                .replace(Fixtures.ACME_TENANT, tenant);
        byte[] request = body.getBytes(StandardCharsets.UTF_8);
        CallContext context = new CallContext("baobab-cp-workload", "synthetic-operator",
                "provider-test-" + UUID.randomUUID(), "synthetic-correlation-" + UUID.randomUUID());

        JsonNode projection = billing.ensure(context, request).body();
        String subscriptionId = projection.path("billing_subscription_id").asText();
        assertEquals("PENDING_CONFIGURATION", projection.path("billing_state").asText());
        assertEquals("BLOCKED", projection.path("readiness").path("status").asText());
        assertEquals(0, observer.observations.get());
        ProviderOperation op = store.transact(tx -> tx.providerOperationForKey(
                tenant, "KILLBILL", "ENSURE_SUBSCRIPTION", context.idempotencyKey())).orElseThrow();
        assertEquals(ProviderOperation.State.PREPARED, op.state());
        assertEquals(projection.path("authoritative_revision").asLong(), op.authoritativeRevision());
        assertEquals(op.operationId(), store.transact(tx -> tx.providerOperationForKey(
                tenant, "KILLBILL", "ENSURE_SUBSCRIPTION", context.idempotencyKey())).orElseThrow().operationId());

        // An idempotent client repeat never creates another provider operation.
        assertTrue(billing.ensure(context, request).replayed());
        ProviderReconciliationService svc = billing.providerOperations();
        assertFalse(svc.find("foreign-" + tenant, op.operationId()).isPresent());

        ProviderOperation sent = svc.claimForDispatch(tenant, op.operationId(), "worker-a", Duration.ofSeconds(2));
        assertEquals(ProviderOperation.State.SENT, sent.state());
        assertEquals(1, sent.leaseEpoch());
        assertThrows(IllegalStateException.class,
                () -> svc.claimForDispatch(tenant, op.operationId(), "worker-b", Duration.ofSeconds(2)));
        assertThrows(IllegalStateException.class,
                () -> svc.reconcile(tenant, op.operationId(), "worker-b", Duration.ofSeconds(2)));

        // A second CP commercial revision may be projected and PREPARED,
        // but must not dispatch while the first could already have created a
        // remote subscription. The older SENT operation remains observable.
        String revisedBody = body.replace(":1,", ":2,")
                .replace("subcls_01k9acmecommercial", "subcls_01k9acmecommercialv2");
        CallContext revisedContext = new CallContext("baobab-cp-workload", "synthetic-operator",
                context.idempotencyKey() + "-revision2", context.correlationId());
        JsonNode revisedProjection = billing.ensure(revisedContext,
                revisedBody.getBytes(StandardCharsets.UTF_8)).body();
        assertEquals(2, revisedProjection.path("authoritative_revision").asLong());
        ProviderOperation replacement = store.transact(tx -> tx.providerOperationForKey(
                tenant, "KILLBILL", "ENSURE_SUBSCRIPTION", revisedContext.idempotencyKey())).orElseThrow();
        assertEquals(ProviderOperation.State.PREPARED, replacement.state());
        assertThrows(IllegalStateException.class,
                () -> svc.claimForDispatch(tenant, replacement.operationId(), "worker-next", Duration.ofSeconds(2)));

        // Lost response: stale dispatch owner cannot assert an outcome after expiry.
        clock.advance(Duration.ofSeconds(3));
        assertThrows(IllegalStateException.class, () -> svc.recordDispatchOutcome(
                tenant, op.operationId(), "worker-a", sent.leaseEpoch(),
                ProviderOutcome.converged("unverified-native-id")));
        assertThrows(IllegalArgumentException.class, () -> svc.claimForDispatch(
                "foreign-" + tenant, op.operationId(), "worker-b", Duration.ofSeconds(2)));

        // Observation failure retains uncertainty without ever repeating money.
        observer.throwOnObserve = true;
        ProviderOperation unknown = svc.reconcile(tenant, op.operationId(), "worker-b", Duration.ofSeconds(2));
        assertEquals(ProviderOperation.State.UNKNOWN_OUTCOME, unknown.state());
        assertEquals("PROVIDER_OBSERVATION_UNAVAILABLE", unknown.reasonCode());
        assertEquals(1, observer.observations.get());
        assertThrows(IllegalStateException.class,
                () -> svc.claimForDispatch(tenant, op.operationId(), "worker-c", Duration.ofSeconds(2)));

        observer.throwOnObserve = false;
        ProviderOperation reconciled = svc.reconcile(tenant, op.operationId(), "worker-c", Duration.ofSeconds(2));
        assertEquals(ProviderOperation.State.RECONCILED, reconciled.state());
        assertEquals(2, observer.observations.get());
        String nativeId = "native-" + op.operationId();
        String mapped = store.transact(tx -> tx.providerMapping(tenant, "KILLBILL", subscriptionId))
                .orElseThrow().providerResourceId();
        assertEquals(nativeId, mapped);
        Projection finalProjection = billing.get(tenant, subscriptionId);
        assertEquals(nativeId, finalProjection.provider().providerReference());
        assertEquals("BLOCKED", finalProjection.readiness().status(),
                "provider observation does not certify pricing, billing account or payments");
        assertEquals("PENDING_CONFIGURATION", finalProjection.billingState().name());
        assertThrows(IllegalStateException.class,
                () -> svc.reconcile(tenant, op.operationId(), "worker-d", Duration.ofSeconds(2)));
        ProviderOperation newerClaim = svc.claimForDispatch(
                tenant, replacement.operationId(), "worker-next", Duration.ofSeconds(2));
        assertEquals(ProviderOperation.State.SENT, newerClaim.state(),
                "a newer intent may dispatch only after earlier uncertainty is reconciled");
        assertEquals(2, observer.observations.get(), "claiming a lease is not a network request");
        // Distinct case: a newer authoritative revision can retire an intent
        // that was NEVER SENT, without treating it as an uncertain dispatch.
        String tenantUnsent = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
        String bodyUnsent = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                .replace(Fixtures.ACME_TENANT, tenantUnsent);
        CallContext firstUnsent = new CallContext("baobab-cp-workload", "synthetic-operator",
                "unsent-" + UUID.randomUUID(), "synthetic-revision");
        billing.ensure(firstUnsent, bodyUnsent.getBytes(StandardCharsets.UTF_8));
        ProviderOperation oldPrepared = store.transact(tx -> tx.providerOperationForKey(
                tenantUnsent, "KILLBILL", "ENSURE_SUBSCRIPTION", firstUnsent.idempotencyKey())).orElseThrow();
        CallContext newerUnsent = new CallContext("baobab-cp-workload", "synthetic-operator",
                "newer-unsent-" + UUID.randomUUID(), "synthetic-revision");
        billing.ensure(newerUnsent, bodyUnsent.replace(":1,", ":2,")
                .replace("subcls_01k9acmecommercial", "subcls_01k9acmecommercialv2")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(ProviderOperation.State.SUPERSEDED,
                svc.find(tenantUnsent, oldPrepared.operationId()).orElseThrow().state());
        ProviderOperation latest = store.transact(tx -> tx.providerOperationForKey(
                tenantUnsent, "KILLBILL", "ENSURE_SUBSCRIPTION", newerUnsent.idempotencyKey())).orElseThrow();
        assertEquals(ProviderOperation.State.PREPARED, latest.state());
        assertEquals(ProviderOperation.State.SENT,
                svc.claimForDispatch(tenantUnsent, latest.operationId(),
                        "worker-unsent", Duration.ofSeconds(2)).state());

        assertNotNull(store.audit(tenant));
        assertTrue(store.audit(tenant).stream()
                        .anyMatch(e -> e.operation().equals("provider_operation.observation_result")),
                "every observed consequence requires durable tenant audit");
    }
}
