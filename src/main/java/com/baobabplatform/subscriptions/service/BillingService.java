package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.domain.AppliedPolicy;
import com.baobabplatform.subscriptions.domain.BillingBlocker;
import com.baobabplatform.subscriptions.domain.BillingState;
import com.baobabplatform.subscriptions.domain.Classification;
import com.baobabplatform.subscriptions.domain.Ids;
import com.baobabplatform.subscriptions.domain.OperationalCondition;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.ProviderRef;
import com.baobabplatform.subscriptions.domain.Readiness;
import com.baobabplatform.subscriptions.domain.ReadinessFacts;
import com.baobabplatform.subscriptions.domain.SubscriptionType;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.policy.BillingPolicy;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.store.AuditRecord;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.ConflictException;
import com.baobabplatform.subscriptions.store.IdempotencyRecord;
import com.baobabplatform.subscriptions.store.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The Baobab Billing API (ADR-SUB-0001, ADR-SUB-0003, ADR-SUB-0006,
 * ADR-SUB-0016; Shared subscriptions/v1).
 *
 * <p>The Control Plane's classification is applied through Shared's billing
 * policy and never evaluated here. Every request and command carries the
 * Control Plane's authoritative revision, so out-of-order delivery never
 * regresses a projection. Readiness is reported as separate facts with
 * machine-readable blockers: INTERNAL is ready with no provider or payment
 * dependency, and billing that requires money names every missing piece while
 * the provider is simulated. Every mutation is idempotent on (tenant,
 * operation, Idempotency-Key), scoped by tenant, and audited in the same
 * transaction. Zero-charge billing never touches the payments port.
 */
public final class BillingService {
    public static final String EVENT_SOURCE = "urn:baobab-platform:service:baobab-subscriptions";
    /** The billing-policy.yaml version applied (recorded in audit evidence). */
    static final int POLICY_VERSION = 1;
    static final String CREATED = "com.baobab-platform.subscriptions.billing-subscription.created.v1";
    static final String SUSPENDED = "com.baobab-platform.subscriptions.billing-subscription.suspended.v1";
    static final String RESUMED = "com.baobab-platform.subscriptions.billing-subscription.resumed.v1";
    static final String TERMINATED = "com.baobab-platform.subscriptions.billing-subscription.terminated.v1";
    static final String USAGE_RECORDED = "com.baobab-platform.subscriptions.usage.recorded.v1";
    private static final Map<String, String> PAYLOAD_DEFS = Map.of(CREATED, "BillingSubscriptionCreated",
            SUSPENDED, "BillingSubscriptionSuspended", RESUMED, "BillingSubscriptionResumed",
            TERMINATED, "BillingSubscriptionTerminated", USAGE_RECORDED, "UsageRecorded");
    private static final int ATTEMPTS = 3;

    private final BillingStore store;
    private final BillingProvider provider;
    private final PaymentsPort payments;
    private final BillingPolicies policies;
    private final Clock clock;

    /** An outcome: the HTTP status and the contract body, and whether it replays an earlier request. */
    public record Result(int status, JsonNode body, boolean replayed) {
    }

    public BillingService(BillingStore store, BillingProvider provider, PaymentsPort payments, BillingPolicies policies, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.provider = Objects.requireNonNull(provider);
        this.payments = Objects.requireNonNull(payments);
        this.policies = Objects.requireNonNull(policies);
        this.clock = Objects.requireNonNull(clock);
    }

    // --- Requests -----------------------------------------------------------

    record EnsureRequest(String tenantId, String productSubscriptionId, long authoritativeRevision, String productId,
            String platformAccountId, String legalEntityId, SubscriptionType subscriptionType, Classification classification) {
    }

    record CommandRequest(String tenantId, long authoritativeRevision, String reason) {
    }

    record UsageRequest(String tenantId, String metricKey, BigDecimal quantity, String unit, Instant occurredAt,
            String sourceReference) {
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = Json.mapper().readTree(body);
            if (node == null || !node.isObject()) {
                throw BillingException.invalid(List.of("$: a JSON object is required"));
            }
            return node;
        } catch (IOException e) {
            throw BillingException.invalid(List.of("$: the body is not JSON"));
        }
    }

    private static <T> T decode(JsonNode node, String definition, Class<T> type) {
        List<String> problems = Contracts.problems(Contracts.def(Contracts.BILLING, definition), node);
        if (!problems.isEmpty()) {
            throw BillingException.invalid(problems);
        }
        try {
            return Json.mapper().treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw BillingException.invalid(List.of("$: " + e.getOriginalMessage()));
        }
    }

    private static String hash(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Json.mapper().writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BillingException stale() {
        return new BillingException(409, "STALE_AUTHORITATIVE_REVISION",
                "the projection already reflects a newer Control Plane revision", false);
    }

    private static BillingException terminated() {
        return new BillingException(409, "BILLING_PROJECTION_TERMINATED", "the billing projection is terminated", false);
    }

    // --- Readiness ----------------------------------------------------------

    /**
     * The lifecycle state and readiness the policy, provider and payment path
     * allow (ADR-SUB-0003 sections 6-10, ADR-SUB-0006 sections 57-58). Only
     * billing that requires money consults the provider's reality, and only a
     * policy that may require payment consults the payments port.
     */
    private Projection settle(Projection p, BillingPolicy policy, BillingState lifecycle, long revision, long version, Instant at) {
        boolean configured = true;
        boolean providerReady = true;
        boolean paymentReady = true;
        List<BillingBlocker> blockers = new ArrayList<>();
        if (policy.billingRequired()) {
            // Pricing, currency and billing accounts are not modelled yet
            // (ADR-SUB-0005, ADR-SUB-0011), so priced billing cannot be configured.
            configured = false;
            blockers.add(BillingBlocker.PRICING_CONFIGURATION_MISSING);
            blockers.add(BillingBlocker.BILLING_ACCOUNT_MISSING);
            providerReady = !p.provider().simulated();
            if (!providerReady) {
                blockers.add(BillingBlocker.BILLING_PROVIDER_NOT_CONFIGURED);
            }
        }
        if (policy.mayRequirePayment()) {
            paymentReady = payments.configured();
            if (!paymentReady) {
                blockers.add(BillingBlocker.PAYMENT_PATH_NOT_READY);
            }
        }
        BillingState state = switch (lifecycle) {
            case SUSPENDED -> {
                blockers.addFirst(BillingBlocker.PROJECTION_SUSPENDED);
                yield BillingState.SUSPENDED;
            }
            case TERMINATING, TERMINATED -> {
                blockers.addFirst(BillingBlocker.PROJECTION_TERMINATED);
                yield lifecycle;
            }
            default -> blockers.isEmpty() ? BillingState.ACTIVE : BillingState.PENDING_CONFIGURATION;
        };
        ReadinessFacts facts = new ReadinessFacts(true, true, true, configured, providerReady, paymentReady);
        return p.with(state, Readiness.of(facts, blockers), revision, version, at);
    }

    // --- Operations ---------------------------------------------------------

    /** Ensures the billing projection of one classified ProductSubscription. */
    public Result ensure(CallContext ctx, byte[] body) {
        JsonNode node = parse(body);
        EnsureRequest req = decode(node, "EnsureBillingProjectionRequest", EnsureRequest.class);
        BillingPolicy policy = policies.forType(req.subscriptionType());
        return idempotent(req.tenantId(), "ensure", ctx, node, tx -> {
            var existing = tx.projectionForProductSubscription(req.tenantId(), req.productSubscriptionId());
            Instant at = now();
            if (existing.isEmpty()) {
                String id = Ids.billingSubscriptionId();
                Projection draft = new Projection(id, req.tenantId(), req.productSubscriptionId(), req.authoritativeRevision(),
                        req.productId(), req.platformAccountId(), req.legalEntityId(), req.subscriptionType(), req.classification(),
                        AppliedPolicy.of(policy), BillingState.PROVISIONING, OperationalCondition.HEALTHY,
                        Readiness.of(new ReadinessFacts(true, true, true, true, true, true), List.of()),
                        new ProviderRef(provider.kind(), provider.simulated(), null), 1, at, at);
                // The temporary provider completes provisioning synchronously; a
                // real provider would leave the projection PROVISIONING until it
                // observes the outcome (ADR-SUB-0003 section 7).
                String reference = provider.ensureSubscription(draft);
                Projection provisioned = new Projection(id, draft.tenantId(), draft.productSubscriptionId(), draft.authoritativeRevision(),
                        draft.productId(), draft.platformAccountId(), draft.legalEntityId(), draft.subscriptionType(),
                        draft.classification(), draft.billingPolicy(), draft.billingState(), draft.operationalCondition(),
                        draft.readiness(), new ProviderRef(provider.kind(), provider.simulated(), reference), 1, at, at);
                Projection created = settle(provisioned, policy, BillingState.ACTIVE, req.authoritativeRevision(), 1, at);
                tx.insertProjection(created);
                ObjectNode data = Json.mapper().createObjectNode()
                        .put("billing_subscription_id", id)
                        .put("product_subscription_id", created.productSubscriptionId())
                        .put("tenant_id", created.tenantId())
                        .put("authoritative_revision", created.authoritativeRevision())
                        .put("subscription_type", created.subscriptionType().name())
                        .put("billing_required", created.billingPolicy().billingRequired())
                        .put("billing_state", created.billingState().name())
                        .put("simulated", created.provider().simulated())
                        .put("created_at", at.toString());
                tx.appendEvent(event(CREATED, created, data, ctx, at));
                tx.appendAudit(audit(ctx, created, "billing_projection.created", null, "classification "
                        + req.classification().classificationId() + " (" + req.subscriptionType() + ")", at));
                return new Result(201, Json.mapper().valueToTree(created), false);
            }
            Projection current = existing.get();
            if (current.billingState().terminal()) {
                throw terminated();
            }
            if (!current.productId().equals(req.productId())) {
                throw new BillingException(409, "PRODUCT_MISMATCH", "the product subscription is billed for another product", false);
            }
            if (req.authoritativeRevision() < current.authoritativeRevision()) {
                throw stale();
            }
            if (sameTerms(current, req)) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            if (req.authoritativeRevision() == current.authoritativeRevision()) {
                throw new BillingException(409, "CLASSIFICATION_REVISION_CONFLICT",
                        "different terms were sent for a revision the projection already reflects", false);
            }
            // A reclassification or changed commercial context: updated in place, never a new identity.
            Projection changed = new Projection(current.billingSubscriptionId(), current.tenantId(), current.productSubscriptionId(),
                    req.authoritativeRevision(), current.productId(), req.platformAccountId(), req.legalEntityId(), req.subscriptionType(),
                    req.classification(), AppliedPolicy.of(policy), current.billingState(), current.operationalCondition(),
                    current.readiness(), current.provider(), current.version(), current.createdAt(), current.updatedAt());
            BillingState lifecycle = current.billingState() == BillingState.SUSPENDED ? BillingState.SUSPENDED : BillingState.ACTIVE;
            Projection updated = settle(changed, policy, lifecycle, req.authoritativeRevision(), current.version() + 1, at);
            tx.updateProjection(updated, current.version());
            tx.appendAudit(audit(ctx, updated, current.subscriptionType() == req.subscriptionType()
                    ? "billing_projection.terms_changed" : "billing_projection.reclassified", current,
                    current.subscriptionType() + " -> " + req.subscriptionType() + " by classification "
                    + req.classification().classificationId(), at));
            return new Result(200, Json.mapper().valueToTree(updated), false);
        });
    }

    private static boolean sameTerms(Projection p, EnsureRequest req) {
        return p.subscriptionType() == req.subscriptionType() && p.classification().equals(req.classification())
                && Objects.equals(p.platformAccountId(), req.platformAccountId())
                && Objects.equals(p.legalEntityId(), req.legalEntityId());
    }

    /** A governed lifecycle command on an existing projection. */
    private Result command(CallContext ctx, String operation, String billingSubscriptionId, byte[] body,
            CommandHandler handler) {
        JsonNode node = parse(body);
        CommandRequest req = decode(node, "BillingProjectionCommand", CommandRequest.class);
        return idempotent(req.tenantId(), operation + ":" + billingSubscriptionId, ctx, node, tx -> {
            Projection current = tx.projection(req.tenantId(), billingSubscriptionId).orElseThrow(BillingException::notFound);
            if (req.authoritativeRevision() < current.authoritativeRevision()) {
                throw stale();
            }
            return handler.handle(tx, current, req, now());
        });
    }

    @FunctionalInterface
    private interface CommandHandler {
        Result handle(BillingStore.Tx tx, Projection current, CommandRequest req, Instant at);
    }

    private Result transition(BillingStore.Tx tx, CallContext ctx, Projection current, CommandRequest req, Instant at,
            BillingState lifecycle, String eventType, String timeField, String action) {
        Projection next = settle(current, policies.forType(current.subscriptionType()), lifecycle,
                Math.max(req.authoritativeRevision(), current.authoritativeRevision()), current.version() + 1, at);
        tx.updateProjection(next, current.version());
        tx.appendEvent(event(eventType, next, Json.mapper().createObjectNode()
                .put("billing_subscription_id", next.billingSubscriptionId())
                .put("product_subscription_id", next.productSubscriptionId())
                .put("tenant_id", next.tenantId())
                .put("authoritative_revision", next.authoritativeRevision())
                .put("billing_state", next.billingState().name())
                .put(timeField, at.toString()), ctx, at));
        tx.appendAudit(audit(ctx, next, action, current, req.reason(), at));
        return new Result(200, Json.mapper().valueToTree(next), false);
    }

    /** Suspends billing by a governed instruction. It does not revoke entitlement (ADR-SUB-0003 section 9). */
    public Result suspend(CallContext ctx, String billingSubscriptionId, byte[] body) {
        return command(ctx, "suspend", billingSubscriptionId, body, (tx, current, req, at) -> {
            if (current.billingState().terminal()) {
                throw terminated();
            }
            if (current.billingState() == BillingState.SUSPENDED) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            provider.suspend(current);
            return transition(tx, ctx, current, req, at, BillingState.SUSPENDED, SUSPENDED, "suspended_at", "billing_projection.suspended");
        });
    }

    /** Resumes a suspended projection; a late resume never resurrects a terminated one (ADR-SUB-0003 section 35). */
    public Result resume(CallContext ctx, String billingSubscriptionId, byte[] body) {
        return command(ctx, "resume", billingSubscriptionId, body, (tx, current, req, at) -> {
            if (current.billingState().terminal()) {
                throw terminated();
            }
            if (current.billingState() != BillingState.SUSPENDED) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            return transition(tx, ctx, current, req, at, BillingState.ACTIVE, RESUMED, "resumed_at", "billing_projection.resumed");
        });
    }

    /**
     * Terminates a projection. TERMINATED is final (ADR-SUB-0003 section 10).
     * The temporary provider completes synchronously; a real provider would
     * hold the projection in TERMINATING until finalisation is observed.
     */
    public Result terminate(CallContext ctx, String billingSubscriptionId, byte[] body) {
        return command(ctx, "terminate", billingSubscriptionId, body, (tx, current, req, at) -> {
            if (current.billingState().terminal()) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            provider.cancel(current);
            return transition(tx, ctx, current, req, at, BillingState.TERMINATED, TERMINATED, "terminated_at",
                    "billing_projection.terminated");
        });
    }

    /** Meters usage. Every type is metered; usage is billable only where the policy charges money. */
    public Result recordUsage(CallContext ctx, String billingSubscriptionId, byte[] body) {
        JsonNode node = parse(body);
        UsageRequest req = decode(node, "RecordUsageRequest", UsageRequest.class);
        return idempotent(req.tenantId(), "usage:" + billingSubscriptionId, ctx, node, tx -> {
            Projection projection = tx.projection(req.tenantId(), billingSubscriptionId).orElseThrow(BillingException::notFound);
            if (projection.billingState().terminal()) {
                throw terminated();
            }
            var prior = tx.usageBySource(req.tenantId(), billingSubscriptionId, req.metricKey(), req.sourceReference());
            if (prior.isPresent()) {
                return new Result(200, Json.mapper().valueToTree(prior.get()), false);
            }
            Instant at = now();
            boolean billable = projection.billingPolicy().billingRequired() && projection.billingPolicy().chargesMoney();
            UsageRecord usage = new UsageRecord(Ids.usageRecordId(), billingSubscriptionId, projection.productSubscriptionId(),
                    req.tenantId(), req.metricKey(), req.quantity(), req.unit(), req.occurredAt(), at, req.sourceReference(), billable);
            provider.recordUsage(projection, usage);
            tx.insertUsage(usage);
            tx.appendEvent(event(USAGE_RECORDED, projection, Json.mapper().createObjectNode()
                    .put("usage_record_id", usage.usageRecordId())
                    .put("billing_subscription_id", billingSubscriptionId)
                    .put("tenant_id", usage.tenantId())
                    .put("metric_key", usage.metricKey())
                    .put("quantity", usage.quantity())
                    .put("unit", usage.unit())
                    .put("billable", billable)
                    .put("occurred_at", usage.occurredAt().toString()), ctx, at));
            return new Result(201, Json.mapper().valueToTree(usage), false);
        });
    }

    /** The tenant's projection; another tenant's identifier is not found. */
    public Projection get(String tenantId, String billingSubscriptionId) {
        return store.transact(tx -> tx.projection(tenantId, billingSubscriptionId)).orElseThrow(BillingException::notFound);
    }

    /** The tenant's projection of a ProductSubscription. */
    public Projection getForProductSubscription(String tenantId, String productSubscriptionId) {
        return store.transact(tx -> tx.projectionForProductSubscription(tenantId, productSubscriptionId))
                .orElseThrow(BillingException::notFound);
    }

    // --- Idempotency, audit and events --------------------------------------

    private Result idempotent(String tenantId, String operation, CallContext ctx, JsonNode request,
            Function<BillingStore.Tx, Result> work) {
        String requestHash = hash(request);
        for (int attempt = 1; ; attempt++) {
            try {
                return store.transact(tx -> {
                    var prior = tx.idempotency(tenantId, operation, ctx.idempotencyKey());
                    if (prior.isPresent()) {
                        if (!prior.get().requestHash().equals(requestHash)) {
                            throw new BillingException(409, "IDEMPOTENCY_KEY_REUSED",
                                    "the Idempotency-Key was used for a different request", false);
                        }
                        return new Result(prior.get().status(), readStored(prior.get().responseBody()), true);
                    }
                    Result result = work.apply(tx);
                    tx.saveIdempotency(new IdempotencyRecord(tenantId, operation, ctx.idempotencyKey(), requestHash, result.status(),
                            result.body().toString()));
                    return result;
                });
            } catch (ConflictException e) {
                if (attempt >= ATTEMPTS) {
                    throw new BillingException(409, "CONCURRENT_MODIFICATION", "a concurrent request changed the projection; retry", true);
                }
            }
        }
    }

    private static JsonNode readStored(String body) {
        try {
            return Json.mapper().readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response is unreadable", e);
        }
    }

    private static String describe(Projection p) {
        return p.subscriptionType() + "/" + p.billingState() + "/" + p.readiness().status();
    }

    private static AuditRecord audit(CallContext ctx, Projection resulting, String operation, Projection previous, String reason,
            Instant at) {
        return new AuditRecord(Ids.uuidV7(), at, "workload", ctx.workloadId(), ctx.actorId(), resulting.tenantId(),
                resulting.platformAccountId(), "billing-projection", resulting.billingSubscriptionId(), operation,
                previous == null ? null : describe(previous), describe(resulting), reason, ctx.idempotencyKey(), ctx.correlationId(),
                resulting.authoritativeRevision(), POLICY_VERSION, resulting.provider().providerReference(), "APPLIED");
    }

    private static OutboxEvent event(String type, Projection subject, ObjectNode data, CallContext ctx, Instant at) {
        UUID id = Ids.uuidV7();
        ObjectNode envelope = Json.mapper().createObjectNode()
                .put("specversion", "1.0")
                .put("id", id.toString())
                .put("type", type)
                .put("source", EVENT_SOURCE)
                .put("subject", "billing-subscription:" + subject.billingSubscriptionId())
                .put("time", at.toString())
                .put("datacontenttype", "application/json")
                .put("dataschema", Contracts.BASE + Contracts.def(Contracts.EVENTS, PAYLOAD_DEFS.get(type)))
                .put("baobabscope", "tenant")
                .put("correlationid", ctx.correlationId())
                .put("tenantid", subject.tenantId())
                .put("idempotencykey", ctx.idempotencyKey());
        envelope.set("data", data);
        return new OutboxEvent(id, type, subject.tenantId(), subject.billingSubscriptionId(), envelope.toString(), at);
    }
}
