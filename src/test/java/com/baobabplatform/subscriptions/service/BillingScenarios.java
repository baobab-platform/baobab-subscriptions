package com.baobabplatform.subscriptions.service;

import static com.baobabplatform.subscriptions.Fixtures.ACME_TENANT;
import static com.baobabplatform.subscriptions.Fixtures.ZURI_TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ADR-SUB-0001 behaviour every store must give. Each subclass supplies a
 * store; every output is checked against the pinned Shared contract.
 */
abstract class BillingScenarios {
    protected BillingStore store;
    protected Fixtures.RecordingPayments payments;
    protected BillingService billing;
    private int keys;

    protected abstract BillingStore newStore();

    /** A per-test prefix, so scenarios sharing a database never collide. */
    protected String unique() {
        return "";
    }

    @BeforeEach
    void setUp() {
        store = newStore();
        payments = new Fixtures.RecordingPayments();
        billing = new BillingService(store, new TemporaryProvider(), payments, BillingPolicies.load(), Fixtures.CLOCK);
    }

    protected String key() {
        return "idem-" + unique() + "-" + (++keys) + "-" + UUID.randomUUID();
    }

    protected static String corr() {
        return UUID.randomUUID().toString();
    }

    protected byte[] scoped(byte[] body) {
        String prefix = unique();
        if (prefix.isEmpty()) {
            return body;
        }
        String s = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                .replace(ZURI_TENANT, ZURI_TENANT + prefix).replace(ACME_TENANT, ACME_TENANT + prefix);
        return Fixtures.json(s);
    }

    protected String zuri() {
        return ZURI_TENANT + unique();
    }

    protected String acme() {
        return ACME_TENANT + unique();
    }

    static void conforms(String definition, JsonNode node) {
        List<String> problems = Contracts.problems(Contracts.def(Contracts.BILLING, definition), node);
        assertTrue(problems.isEmpty(), definition + " breaks the contract: " + problems + "\n" + node);
    }

    void eventsConform(String tenant) throws Exception {
        for (OutboxEvent e : store.events(tenant)) {
            JsonNode envelope = Json.mapper().readTree(e.envelopeJson());
            assertTrue(Contracts.problems(Contracts.ENVELOPE, envelope).isEmpty(), "envelope: " + Contracts.problems(Contracts.ENVELOPE, envelope));
            String def = envelope.get("dataschema").asText().substring(envelope.get("dataschema").asText().lastIndexOf('/') + 1);
            List<String> problems = Contracts.problems(Contracts.def(Contracts.EVENTS, def), envelope.get("data"));
            assertTrue(problems.isEmpty(), e.eventType() + ": " + problems);
            assertTrue(e.eventType().startsWith("com.baobab-platform.subscriptions."), e.eventType());
        }
    }

    @Test
    void internalIsReadyAtZeroChargeAndNeverTouchesPayments() throws Exception {
        var created = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr());
        assertEquals(201, created.status());
        JsonNode p = created.body();
        conforms("BillingProjection", p);
        assertEquals("INTERNAL", p.get("subscription_type").asText());
        assertEquals("ZERO", p.at("/billing_policy/monetary_charge").asText());
        assertFalse(p.at("/billing_policy/billing_required").asBoolean());
        assertEquals("NEVER", p.at("/billing_policy/payment_execution").asText());
        assertTrue(p.at("/billing_policy/usage_metering").asBoolean());
        assertEquals("ACTIVE", p.get("billing_state").asText());
        assertEquals("READY", p.at("/readiness/status").asText());
        assertEquals(0, p.at("/readiness/reasons").size());
        assertEquals("TEMPORARY", p.at("/provider/kind").asText());
        assertTrue(p.at("/provider/simulated").asBoolean());
        assertEquals("adm_01k9zuribeans", p.at("/classification/classification_reference").asText());

        var usage = billing.recordUsage(key(), p.get("billing_subscription_id").asText(), scoped(Fixtures.usage(ZURI_TENANT, "meter-batch-1")), corr());
        assertEquals(201, usage.status());
        conforms("UsageRecord", usage.body());
        assertFalse(usage.body().get("billable").asBoolean(), "INTERNAL usage is metered, not billable");

        billing.suspend(key(), p.get("billing_subscription_id").asText(), scoped(Fixtures.command(ZURI_TENANT, "review")), corr());
        billing.cancel(key(), p.get("billing_subscription_id").asText(), scoped(Fixtures.command(ZURI_TENANT, "ended")), corr());
        assertEquals(0, payments.calls.get(), "INTERNAL billing must never touch the payments port");

        List<String> types = store.events(zuri()).stream().map(OutboxEvent::eventType).toList();
        assertEquals(List.of(BillingService.CREATED, BillingService.USAGE_RECORDED, BillingService.SUSPENDED, BillingService.CANCELLED), types);
        eventsConform(zuri());
    }

    @Test
    void commercialOnTheTemporaryProviderIsHonestlyBlocked() {
        JsonNode p = billing.ensure(key(), scoped(Fixtures.acmeCommercial()), corr()).body();
        conforms("BillingProjection", p);
        assertEquals("PENDING_CONFIGURATION", p.get("billing_state").asText());
        assertNotEquals("ACTIVE", p.get("billing_state").asText());
        assertEquals("BLOCKED", p.at("/readiness/status").asText());
        List<String> reasons = new ArrayList<>();
        p.at("/readiness/reasons").forEach(r -> reasons.add(r.asText()));
        assertEquals(List.of("BILLING_PROVIDER_NOT_CONFIGURED", "PAYMENT_PROVIDER_NOT_CONFIGURED"), reasons);
        assertTrue(p.at("/billing_policy/billing_required").asBoolean());

        var usage = billing.recordUsage(key(), p.get("billing_subscription_id").asText(), scoped(Fixtures.usage(ACME_TENANT, "meter-1")), corr());
        assertTrue(usage.body().get("billable").asBoolean(), "COMMERCIAL usage is billable");
    }

    @Test
    void ensureIsIdempotentAndConverges() {
        String k = key();
        var first = billing.ensure(k, scoped(Fixtures.zuriInternal()), corr());
        var replay = billing.ensure(k, scoped(Fixtures.zuriInternal()), corr());
        assertEquals(201, replay.status());
        assertTrue(replay.replayed());
        assertEquals(first.body().toString(), replay.body().toString());

        var again = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr());
        assertEquals(200, again.status(), "a new key for the same subscription converges on the projection");
        assertEquals(first.body().get("billing_subscription_id"), again.body().get("billing_subscription_id"));

        BillingException reused = assertThrows(BillingException.class, () -> billing.ensure(k, scoped(Fixtures.zuriReclassifiedCommercial()), corr()));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(1, store.events(zuri()).size(), "a replay publishes nothing");
    }

    @Test
    void reclassificationUpdatesTheProjectionInPlace() {
        JsonNode internal = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr()).body();
        JsonNode commercial = billing.ensure(key(), scoped(Fixtures.zuriReclassifiedCommercial()), corr()).body();
        conforms("BillingProjection", commercial);
        assertEquals(internal.get("billing_subscription_id"), commercial.get("billing_subscription_id"));
        assertEquals("COMMERCIAL", commercial.get("subscription_type").asText());
        assertEquals("BLOCKED", commercial.at("/readiness/status").asText());
        assertEquals(2, commercial.get("version").asInt());
        assertEquals("RECLASSIFICATION", commercial.at("/classification/classification_source").asText());

        BillingException stale = assertThrows(BillingException.class, () -> billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr()));
        assertEquals("STALE_CLASSIFICATION", stale.code(), "an out-of-order older classification never wins");
    }

    @Test
    void tenantsAreIsolated() {
        String id = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr()).body().get("billing_subscription_id").asText();
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class, () -> billing.get(acme(), id)).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.suspend(key(), id, scoped(Fixtures.command(ACME_TENANT, "x")), corr())).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.recordUsage(key(), id, scoped(Fixtures.usage(ACME_TENANT, "s")), corr())).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.getForProductSubscription(acme(), "sub_01k9zuribeansxbt")).code());
        assertEquals(id, billing.get(zuri(), id).billingSubscriptionId());
    }

    @Test
    void suspensionAndCancellationShowInReadiness() {
        String id = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr()).body().get("billing_subscription_id").asText();
        JsonNode suspended = billing.suspend(key(), id, scoped(Fixtures.command(ZURI_TENANT, "governed review")), corr()).body();
        conforms("BillingProjection", suspended);
        assertEquals("SUSPENDED", suspended.get("billing_state").asText());
        assertEquals("PROJECTION_SUSPENDED", suspended.at("/readiness/reasons/0").asText());
        JsonNode cancelled = billing.cancel(key(), id, scoped(Fixtures.command(ZURI_TENANT, "subscription ended")), corr()).body();
        conforms("BillingProjection", cancelled);
        assertEquals("CANCELLED", cancelled.get("billing_state").asText());
        assertEquals("BILLING_PROJECTION_CANCELLED", assertThrows(BillingException.class,
                () -> billing.recordUsage(key(), id, scoped(Fixtures.usage(ZURI_TENANT, "late")), corr())).code());
        assertEquals("BILLING_PROJECTION_CANCELLED", assertThrows(BillingException.class,
                () -> billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr())).code());
    }

    @Test
    void usageIsMeteredOncePerSource() {
        String id = billing.ensure(key(), scoped(Fixtures.zuriInternal()), corr()).body().get("billing_subscription_id").asText();
        var first = billing.recordUsage(key(), id, scoped(Fixtures.usage(ZURI_TENANT, "batch-7")), corr());
        var duplicate = billing.recordUsage(key(), id, scoped(Fixtures.usage(ZURI_TENANT, "batch-7")), corr());
        assertEquals(201, first.status());
        assertEquals(200, duplicate.status());
        assertEquals(first.body().get("usage_record_id"), duplicate.body().get("usage_record_id"));
    }

    @Test
    void requestsOutsideTheContractAreRefused() {
        BillingException evidence = assertThrows(BillingException.class, () -> billing.ensure(key(), scoped(Fixtures.json("""
                {"tenant_id":"tn_01k9x","product_subscription_id":"sub_01k9x","product_id":"baobab-xbt","subscription_type":"INTERNAL",
                 "classification":{"classification_id":"subcls_01k9x","classification_source":"ADMISSION_DECISION",
                   "classification_reference":"adm_01k9x","classified_at":"2026-09-22T10:05:00Z"},
                 "internal_eligibility":{"eligibility_status":"ELIGIBLE"}}""")), corr()));
        assertEquals("VALIDATION_FAILED", evidence.code(), "an ensure request can never carry eligibility evidence");
        assertEquals("VALIDATION_FAILED", assertThrows(BillingException.class,
                () -> billing.ensure(key(), Fixtures.json("[]"), corr())).code());
        assertEquals("VALIDATION_FAILED", assertThrows(BillingException.class,
                () -> billing.ensure(key(), Fixtures.json("{\"tenant_id\":\"not-a-tenant\"}"), corr())).code());
    }

    @Test
    void concurrentEnsuresCreateOneProjection() throws Exception {
        int n = 8;
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> ids.add(billing.ensure(key(), scoped(Fixtures.acmeCommercial()), corr())
                        .body().get("billing_subscription_id").asText())));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(1, ids.size(), "concurrent ensures converge on one projection");
        assertEquals(1, store.events(acme()).size());
    }
}
