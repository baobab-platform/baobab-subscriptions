package com.baobabplatform.subscriptions.service;

import static com.baobabplatform.subscriptions.Fixtures.ACME_TENANT;
import static com.baobabplatform.subscriptions.Fixtures.ZURI_TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.store.AuditRecord;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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
 * The ADR-SUB-0001/0003/0006/0016 behaviour every store must give. Each
 * subclass supplies a store; every output is checked against the pinned
 * Shared contract.
 */
abstract class BillingScenarios {
    protected BillingStore store;
    protected Fixtures.RecordingPayments payments;
    protected BillingService billing;
    private int keys;

    protected abstract BillingStore newStore();

    /** A per-test suffix, so scenarios sharing a database never collide. */
    protected String unique() {
        return "";
    }

    @BeforeEach
    void setUp() {
        store = newStore();
        payments = new Fixtures.RecordingPayments();
        billing = new BillingService(store, new TemporaryProvider(), payments, BillingPolicies.load(), Fixtures.CLOCK);
    }

    protected CallContext ctx() {
        return new CallContext("baobab-cp-workload", "service-account-cp", "idem-" + unique() + "-" + (++keys) + "-" + UUID.randomUUID(),
                UUID.randomUUID().toString());
    }

    protected byte[] scoped(byte[] body) {
        String prefix = unique();
        if (prefix.isEmpty()) {
            return body;
        }
        String s = new String(body, StandardCharsets.UTF_8)
                .replace(ZURI_TENANT, ZURI_TENANT + prefix).replace(ACME_TENANT, ACME_TENANT + prefix);
        return Fixtures.json(s);
    }

    protected String zuri() {
        return ZURI_TENANT + unique();
    }

    protected String acme() {
        return ACME_TENANT + unique();
    }

    private byte[] cmd(String tenant, long revision, String reason) {
        return scoped(Fixtures.command(tenant, revision, reason));
    }

    static void conforms(String definition, JsonNode node) {
        List<String> problems = Contracts.problems(Contracts.def(Contracts.BILLING, definition), node);
        assertTrue(problems.isEmpty(), definition + " breaks the contract: " + problems + "\n" + node);
    }

    static List<String> blockers(JsonNode projection) {
        List<String> out = new ArrayList<>();
        projection.at("/readiness/blockers").forEach(b -> out.add(b.asText()));
        return out;
    }

    void eventsConform(String tenant) throws Exception {
        for (OutboxEvent e : store.events(tenant)) {
            JsonNode envelope = Json.mapper().readTree(e.envelopeJson());
            assertTrue(Contracts.problems(Contracts.ENVELOPE, envelope).isEmpty(), "envelope: " + Contracts.problems(Contracts.ENVELOPE, envelope));
            String schema = envelope.get("dataschema").asText();
            List<String> problems = Contracts.problems(Contracts.def(Contracts.EVENTS, schema.substring(schema.lastIndexOf('/') + 1)),
                    envelope.get("data"));
            assertTrue(problems.isEmpty(), e.eventType() + ": " + problems);
            assertTrue(e.eventType().startsWith("com.baobab-platform.subscriptions."), e.eventType());
        }
    }

    @Test
    void internalIsReadyAtZeroChargeAndNeverTouchesPayments() throws Exception {
        var created = billing.ensure(ctx(), scoped(Fixtures.zuriInternal()));
        assertEquals(201, created.status());
        JsonNode p = created.body();
        conforms("BillingProjection", p);
        assertEquals("INTERNAL", p.get("subscription_type").asText());
        assertEquals("ZERO", p.at("/billing_policy/monetary_charge").asText());
        assertFalse(p.at("/billing_policy/billing_required").asBoolean());
        assertEquals("NEVER", p.at("/billing_policy/payment_execution").asText());
        assertEquals("ACTIVE", p.get("billing_state").asText());
        assertEquals("HEALTHY", p.get("operational_condition").asText());
        assertEquals("READY", p.at("/readiness/status").asText());
        assertEquals(List.of(), blockers(p));
        assertTrue(p.at("/readiness/facts/metering_available").asBoolean());
        assertEquals(2, p.get("authoritative_revision").asInt());
        assertTrue(p.at("/provider/simulated").asBoolean());

        String id = p.get("billing_subscription_id").asText();
        var usage = billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ZURI_TENANT, "meter-batch-1")));
        conforms("UsageRecord", usage.body());
        assertFalse(usage.body().get("billable").asBoolean(), "INTERNAL usage is metered, not billable");

        billing.suspend(ctx(), id, cmd(ZURI_TENANT, 3, "review"));
        billing.resume(ctx(), id, cmd(ZURI_TENANT, 4, "review closed"));
        billing.terminate(ctx(), id, cmd(ZURI_TENANT, 5, "ended"));
        assertEquals(0, payments.calls.get(), "INTERNAL billing must never touch the payments port");

        List<String> types = store.events(zuri()).stream().map(OutboxEvent::eventType).toList();
        assertEquals(List.of(BillingService.CREATED, BillingService.USAGE_RECORDED, BillingService.SUSPENDED, BillingService.RESUMED,
                BillingService.TERMINATED), types);
        eventsConform(zuri());
    }

    @Test
    void commercialOnTheTemporaryProviderNamesEveryMissingPiece() {
        JsonNode p = billing.ensure(ctx(), scoped(Fixtures.acmeCommercial())).body();
        conforms("BillingProjection", p);
        assertEquals("PENDING_CONFIGURATION", p.get("billing_state").asText());
        assertNotEquals("ACTIVE", p.get("billing_state").asText());
        assertEquals("BLOCKED", p.at("/readiness/status").asText());
        assertEquals(List.of("PRICING_CONFIGURATION_MISSING", "BILLING_ACCOUNT_MISSING", "BILLING_PROVIDER_NOT_CONFIGURED",
                "PAYMENT_PATH_NOT_READY"), blockers(p));
        assertFalse(p.at("/readiness/facts/provider_ready").asBoolean());
        assertFalse(p.at("/readiness/facts/payment_path_ready").asBoolean());
        assertFalse(p.at("/readiness/facts/billing_configuration_complete").asBoolean());
        assertTrue(p.at("/readiness/facts/classification_valid").asBoolean());

        var usage = billing.recordUsage(ctx(), p.get("billing_subscription_id").asText(), scoped(Fixtures.usage(ACME_TENANT, "meter-1")));
        assertTrue(usage.body().get("billable").asBoolean(), "COMMERCIAL usage is billable");
    }

    @Test
    void ensureIsIdempotentAndConverges() {
        CallContext first = ctx();
        var created = billing.ensure(first, scoped(Fixtures.zuriInternal()));
        var replay = billing.ensure(first, scoped(Fixtures.zuriInternal()));
        assertEquals(201, replay.status());
        assertTrue(replay.replayed());
        assertEquals(created.body().toString(), replay.body().toString());

        var again = billing.ensure(ctx(), scoped(Fixtures.zuriInternal()));
        assertEquals(200, again.status(), "a new key for the same subscription converges on the projection");
        assertEquals(created.body().get("billing_subscription_id"), again.body().get("billing_subscription_id"));

        BillingException reused = assertThrows(BillingException.class, () -> billing.ensure(first, scoped(Fixtures.zuriReclassifiedCommercial())));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(1, store.events(zuri()).size(), "a replay publishes nothing");
    }

    @Test
    void reclassificationFollowsTheAuthoritativeRevision() {
        JsonNode internal = billing.ensure(ctx(), scoped(Fixtures.zuriInternal())).body();
        JsonNode commercial = billing.ensure(ctx(), scoped(Fixtures.zuriReclassifiedCommercial())).body();
        conforms("BillingProjection", commercial);
        assertEquals(internal.get("billing_subscription_id"), commercial.get("billing_subscription_id"));
        assertEquals("COMMERCIAL", commercial.get("subscription_type").asText());
        assertEquals("BLOCKED", commercial.at("/readiness/status").asText());
        assertEquals(5, commercial.get("authoritative_revision").asInt());
        assertEquals(2, commercial.get("version").asInt());

        BillingException stale = assertThrows(BillingException.class, () -> billing.ensure(ctx(), scoped(Fixtures.zuriInternal())));
        assertEquals("STALE_AUTHORITATIVE_REVISION", stale.code(), "an older revision never regresses the projection");
        byte[] conflicting = scoped(Fixtures.json(new String(Fixtures.zuriReclassifiedCommercial(), StandardCharsets.UTF_8)
                .replace("subcls_01k9zuricommercial", "subcls_01k9zuriother")));
        assertEquals("CLASSIFICATION_REVISION_CONFLICT", assertThrows(BillingException.class,
                () -> billing.ensure(ctx(), conflicting)).code());
        assertEquals("COMMERCIAL", billing.get(zuri(), internal.get("billing_subscription_id").asText()).subscriptionType().name());
    }

    @Test
    void lifecycleCommandsRespectRevisionsAndFinality() {
        String id = billing.ensure(ctx(), scoped(Fixtures.zuriInternal())).body().get("billing_subscription_id").asText();
        JsonNode suspended = billing.suspend(ctx(), id, cmd(ZURI_TENANT, 3, "governed review")).body();
        conforms("BillingProjection", suspended);
        assertEquals("SUSPENDED", suspended.get("billing_state").asText());
        assertEquals("PROJECTION_SUSPENDED", blockers(suspended).getFirst());

        assertEquals("STALE_AUTHORITATIVE_REVISION", assertThrows(BillingException.class,
                () -> billing.resume(ctx(), id, cmd(ZURI_TENANT, 2, "late resume"))).code(), "a delayed resume never undoes a newer suspension");
        JsonNode resumed = billing.resume(ctx(), id, cmd(ZURI_TENANT, 4, "review closed")).body();
        assertEquals("ACTIVE", resumed.get("billing_state").asText());
        assertEquals("READY", resumed.at("/readiness/status").asText());

        JsonNode terminated = billing.terminate(ctx(), id, cmd(ZURI_TENANT, 6, "subscription ended")).body();
        conforms("BillingProjection", terminated);
        assertEquals("TERMINATED", terminated.get("billing_state").asText());
        assertEquals("PROJECTION_TERMINATED", blockers(terminated).getFirst());
        assertEquals("BILLING_PROJECTION_TERMINATED", assertThrows(BillingException.class,
                () -> billing.resume(ctx(), id, cmd(ZURI_TENANT, 7, "resurrect"))).code(), "TERMINATED is final");
        assertEquals("BILLING_PROJECTION_TERMINATED", assertThrows(BillingException.class,
                () -> billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ZURI_TENANT, "late")))).code());
        assertEquals("BILLING_PROJECTION_TERMINATED", assertThrows(BillingException.class,
                () -> billing.ensure(ctx(), scoped(Fixtures.zuriReclassifiedCommercial()))).code());
    }

    @Test
    void auditRecordsCarryTheEvidence() {
        CallContext creating = ctx();
        String id = billing.ensure(creating, scoped(Fixtures.zuriInternal())).body().get("billing_subscription_id").asText();
        billing.ensure(ctx(), scoped(Fixtures.zuriReclassifiedCommercial()));
        billing.suspend(ctx(), id, cmd(ZURI_TENANT, 6, "payment dispute"));
        billing.terminate(ctx(), id, cmd(ZURI_TENANT, 7, "contract ended"));
        assertThrows(BillingException.class, () -> billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ZURI_TENANT, "late"))));
        List<AuditRecord> audit = store.audit(zuri());
        assertEquals(List.of("billing_projection.created", "billing_projection.reclassified", "billing_projection.suspended",
                "billing_projection.terminated"), audit.stream().map(AuditRecord::operation).toList());
        AuditRecord created = audit.getFirst();
        assertEquals("workload", created.principalType());
        assertEquals("baobab-cp-workload", created.workloadId());
        assertEquals(creating.idempotencyKey(), created.idempotencyKey());
        assertEquals(creating.correlationId(), created.correlationId());
        assertEquals(id, created.resourceId());
        assertNull(created.previousState());
        assertEquals("INTERNAL/ACTIVE/READY", created.resultingState());
        assertEquals(2L, created.authoritativeRevision());
        assertEquals(1, created.policyVersion());
        AuditRecord reclassified = audit.get(1);
        assertEquals("INTERNAL/ACTIVE/READY", reclassified.previousState());
        assertEquals("COMMERCIAL/PENDING_CONFIGURATION/BLOCKED", reclassified.resultingState());
        assertTrue(reclassified.reason().contains("INTERNAL -> COMMERCIAL"), reclassified.reason());
        assertEquals("payment dispute", audit.get(2).reason());
    }

    @Test
    void tenantsAreIsolated() {
        String id = billing.ensure(ctx(), scoped(Fixtures.zuriInternal())).body().get("billing_subscription_id").asText();
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class, () -> billing.get(acme(), id)).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.suspend(ctx(), id, cmd(ACME_TENANT, 9, "x"))).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ACME_TENANT, "s")))).code());
        assertEquals("BILLING_PROJECTION_NOT_FOUND", assertThrows(BillingException.class,
                () -> billing.getForProductSubscription(acme(), "sub_01k9zuribeansxbt")).code());
        assertEquals(id, billing.get(zuri(), id).billingSubscriptionId());
        assertTrue(store.audit(acme()).isEmpty());
    }

    @Test
    void usageIsMeteredOncePerSource() {
        String id = billing.ensure(ctx(), scoped(Fixtures.zuriInternal())).body().get("billing_subscription_id").asText();
        var first = billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ZURI_TENANT, "batch-7")));
        var duplicate = billing.recordUsage(ctx(), id, scoped(Fixtures.usage(ZURI_TENANT, "batch-7")));
        assertEquals(201, first.status());
        assertEquals(200, duplicate.status());
        assertEquals(first.body().get("usage_record_id"), duplicate.body().get("usage_record_id"));
    }

    @Test
    void requestsOutsideTheContractAreRefused() {
        BillingException evidence = assertThrows(BillingException.class, () -> billing.ensure(ctx(), scoped(Fixtures.json("""
                {"tenant_id":"tn_01k9x","product_subscription_id":"sub_01k9x","authoritative_revision":1,"product_id":"baobab-xbt",
                 "subscription_type":"INTERNAL",
                 "classification":{"classification_id":"subcls_01k9x","classification_source":"ADMISSION_DECISION",
                   "classification_reference":"adm_01k9x","classified_at":"2026-09-22T10:05:00Z"},
                 "internal_eligibility":{"eligibility_status":"ELIGIBLE"}}"""))));
        assertEquals("VALIDATION_FAILED", evidence.code(), "an ensure request can never carry eligibility evidence");
        byte[] noRevision = Fixtures.json(new String(Fixtures.zuriInternal(), StandardCharsets.UTF_8).replace("\"authoritative_revision\":2,", ""));
        assertEquals("VALIDATION_FAILED", assertThrows(BillingException.class, () -> billing.ensure(ctx(), noRevision)).code());
        assertEquals("VALIDATION_FAILED", assertThrows(BillingException.class, () -> billing.ensure(ctx(), Fixtures.json("[]"))).code());
    }

    @Test
    void concurrentEnsuresCreateOneProjection() throws Exception {
        int n = 8;
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> ids.add(billing.ensure(ctx(), scoped(Fixtures.acmeCommercial()))
                        .body().get("billing_subscription_id").asText())));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(1, ids.size(), "concurrent ensures converge on one projection");
        assertEquals(1, store.events(acme()).size());
        assertEquals(1, store.audit(acme()).size());
    }
}
