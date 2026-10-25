package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.*;
import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CurrentProjectionAuthorityTest {
    @Test void currentAuthorityPrecedesPreviouslySuccessfulEnsureReplay() {
        try (var store = new InMemoryBillingStore()) {
            var allowed = new AtomicBoolean(true);
            var observed = new AtomicInteger();
            var service = new BillingService(store,new TemporaryProvider(),PaymentsPort.notConfigured(),
                    BillingPolicies.load(),Clock.systemUTC(),InternalAuthorityGate.unavailable(),
                    (tenant,subscription,revision,product,type,classification) -> {
                        observed.incrementAndGet();
                        assertEquals(Fixtures.ACME_TENANT,tenant);
                        assertEquals(1,revision);
                        return allowed.get();
                    });
            var ctx = new CallContext("baobab-dev-cp-subscriptions","synthetic", "ensure-"+UUID.randomUUID(),UUID.randomUUID().toString());
            var first = service.ensure(ctx,Fixtures.acmeCommercial());
            assertEquals(201,first.status());
            allowed.set(false);
            var denied = assertThrows(BillingException.class,()->service.ensure(ctx,Fixtures.acmeCommercial()));
            assertEquals("SUBSCRIPTION_AUTHORITY_NOT_CURRENT",denied.code());
            allowed.set(true);
            assertTrue(service.ensure(ctx,Fixtures.acmeCommercial()).replayed());
            assertEquals(3,observed.get());
        }
    }
}
