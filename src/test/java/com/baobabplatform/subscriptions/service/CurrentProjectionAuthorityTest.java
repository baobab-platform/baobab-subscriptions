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
    @Test void usageChecksCpOnceOutsideTransactionsAndRefusesConcurrentProjectionChange() {
        try (var delegate = new InMemoryBillingStore()) {
            var depth = new AtomicInteger();
            var calls = new AtomicInteger();
            com.baobabplatform.subscriptions.store.BillingStore tracked = new com.baobabplatform.subscriptions.store.BillingStore() {
                @Override public <T> T transact(Work<T> work) {
                    return delegate.transact(tx -> { depth.incrementAndGet(); try { return work.run(tx); }
                        finally { depth.decrementAndGet(); } });
                }
                @Override public boolean ping() { return delegate.ping(); }
                @Override public java.util.List<com.baobabplatform.subscriptions.store.OutboxEvent> events(String tenant) { return delegate.events(tenant); }
                @Override public java.util.List<com.baobabplatform.subscriptions.store.AuditRecord> audit(String tenant) { return delegate.audit(tenant); }
                @Override public void close() { }
            };
            var duringAuthority = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> { });
            var service = new BillingService(tracked,new TemporaryProvider(),PaymentsPort.notConfigured(),
                    BillingPolicies.load(),Clock.systemUTC(),InternalAuthorityGate.unavailable(),
                    (tenant,subscription,revision,product,type,classification) -> {
                        assertEquals(0,depth.get(),"remote CP work must not hold a store transaction");
                        calls.incrementAndGet();duringAuthority.getAndSet(() -> { }).run();return true;
                    });
            var ctx = new CallContext("baobab-dev-cp-subscriptions","synthetic","ensure-"+UUID.randomUUID(),UUID.randomUUID().toString());
            String id = service.ensure(ctx,Fixtures.acmeCommercial()).body().path("billing_subscription_id").asText();
            calls.set(0);
            var usageCtx = new CallContext(ctx.workloadId(),"synthetic","usage-"+UUID.randomUUID(),UUID.randomUUID().toString());
            service.recordUsage(usageCtx,id,Fixtures.usage(Fixtures.ACME_TENANT,"first"));
            assertEquals(1,calls.get());
            duringAuthority.set(() -> service.suspend(new CallContext(ctx.workloadId(),"synthetic",
                    "suspend-"+UUID.randomUUID(),UUID.randomUUID().toString()),id,Fixtures.command(Fixtures.ACME_TENANT,2,"synthetic race")));
            var failure = assertThrows(BillingException.class,()->service.recordUsage(
                    new CallContext(ctx.workloadId(),"synthetic","usage-"+UUID.randomUUID(),UUID.randomUUID().toString()),
                    id,Fixtures.usage(Fixtures.ACME_TENANT,"second")));
            assertEquals("PROJECTION_CHANGED_DURING_AUTHORITY_CHECK",failure.code());
            assertEquals(2,calls.get(),"no second remote check while write transaction is open");
        }
    }

    @Test void resumeChecksTheIncomingCpRevisionBeforeAdvancingOrReplaying() {
        try (var store = new InMemoryBillingStore()) {
            var cpRevision = new java.util.concurrent.atomic.AtomicLong(1);
            var service = new BillingService(store,new TemporaryProvider(),PaymentsPort.notConfigured(),
                    BillingPolicies.load(),Clock.systemUTC(),InternalAuthorityGate.unavailable(),
                    (tenant,subscription,revision,product,type,classification) -> revision==cpRevision.get());
            var ctx = new CallContext("baobab-dev-cp-subscriptions","synthetic","ensure-"+UUID.randomUUID(),UUID.randomUUID().toString());
            String id=service.ensure(ctx,Fixtures.acmeCommercial()).body().path("billing_subscription_id").asText();
            service.suspend(new CallContext(ctx.workloadId(),"synthetic","suspend-"+UUID.randomUUID(),UUID.randomUUID().toString()),
                    id,Fixtures.command(Fixtures.ACME_TENANT,2,"suspend"));
            cpRevision.set(3);
            var resumeCtx=new CallContext(ctx.workloadId(),"synthetic","resume-"+UUID.randomUUID(),UUID.randomUUID().toString());
            var result=service.resume(resumeCtx,id,Fixtures.command(Fixtures.ACME_TENANT,3,"resume"));
            assertEquals(3,result.body().path("authoritative_revision").asLong());
            assertThrows(BillingException.class,()->service.resume(
                    new CallContext(ctx.workloadId(),"synthetic","resume-"+UUID.randomUUID(),UUID.randomUUID().toString()),
                    id,Fixtures.command(Fixtures.ACME_TENANT,4,"unverified revision")));
            cpRevision.set(4);
            assertThrows(BillingException.class,()->service.resume(resumeCtx,id,Fixtures.command(Fixtures.ACME_TENANT,3,"resume")));
        }
    }

}
