package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A historical INTERNAL projection is never an ongoing sponsorship grant. */
final class InternalAuthorityGateTest {
    @Test
    void noCurrentAuthorityDeniesInternalButNotCommercial() {
        var service = new BillingService(new InMemoryBillingStore(),
                new TemporaryProvider(), PaymentsPort.notConfigured(),
                BillingPolicies.load(), Fixtures.CLOCK);
        var ctx = new CallContext("baobab-cp-workload", "test-workload",
                "test-only-idempotency-key-001", UUID.randomUUID().toString());
        BillingException denied = assertThrows(BillingException.class,
                () -> service.ensure(ctx, Fixtures.syntheticInternal()));
        assertEquals("INTERNAL_AUTHORITY_NOT_CURRENT", denied.code());
        assertEquals(201, service.ensure(ctx, Fixtures.acmeCommercial()).status());
    }

    @Test
    void aRevokedAuthorityDeniesReplayedEnsureUsageAndResume() {
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = new BillingService(new InMemoryBillingStore(),
                new TemporaryProvider(), PaymentsPort.notConfigured(),
                BillingPolicies.load(), Fixtures.CLOCK,
                (tenant, subscription, reference) -> allowed.get());
        var ctx = new CallContext("baobab-cp-workload", "test-workload",
                "test-only-idempotency-key-002", UUID.randomUUID().toString());
        var created = service.ensure(ctx, Fixtures.syntheticInternal()).body();
        String id = created.get("billing_subscription_id").asText();
        allowed.set(false);
        assertEquals("INTERNAL_AUTHORITY_NOT_CURRENT", assertThrows(BillingException.class,
                () -> service.ensure(ctx, Fixtures.syntheticInternal())).code());
        assertEquals("INTERNAL_AUTHORITY_NOT_CURRENT", assertThrows(BillingException.class,
                () -> service.recordUsage(ctx, id, Fixtures.usage(Fixtures.SYNTHETIC_TENANT, "test-only-usage"))).code());
        assertEquals("INTERNAL_AUTHORITY_NOT_CURRENT", assertThrows(BillingException.class,
                () -> service.resume(ctx, id, Fixtures.command(Fixtures.SYNTHETIC_TENANT, 3,
                        "synthetic resume denied without authority"))).code());
    }

    @Test
    void unavailableAuthorityAdapterCannotFailOpen() {
        var service = new BillingService(new InMemoryBillingStore(),
                new TemporaryProvider(), PaymentsPort.notConfigured(),
                BillingPolicies.load(), Fixtures.CLOCK,
                (tenant, subscription, reference) -> {
                    throw new IllegalStateException("CP unavailable");
                });
        var ctx = new CallContext("baobab-cp-workload", "test-workload",
                "test-only-idempotency-key-003", UUID.randomUUID().toString());
        assertEquals("INTERNAL_AUTHORITY_NOT_CURRENT", assertThrows(BillingException.class,
                () -> service.ensure(ctx, Fixtures.syntheticInternal())).code());
    }
}
