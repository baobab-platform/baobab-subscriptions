package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.domain.AppliedPolicy;
import com.baobabplatform.subscriptions.domain.BillingState;
import com.baobabplatform.subscriptions.domain.Classification;
import com.baobabplatform.subscriptions.domain.Ids;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.ProviderRef;
import com.baobabplatform.subscriptions.domain.Readiness;
import com.baobabplatform.subscriptions.domain.ReadinessReason;
import com.baobabplatform.subscriptions.domain.SubscriptionType;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.policy.BillingPolicy;
import com.baobabplatform.subscriptions.provider.BillingProvider;
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
 * The Baobab Billing API (ADR-SUB-0001, Shared subscriptions/v1).
 *
 * <p>It applies the Control Plane's classification through Shared's billing
 * policy and never evaluates it. Readiness is honest: INTERNAL is ready with no
 * payment dependency, and billing that requires money is BLOCKED with precise
 * reasons while the provider is simulated. Every mutation is idempotent on
 * (tenant, operation, Idempotency-Key) and every read and write is scoped by
 * tenant. Zero-charge billing never touches the payments port.
 */
public final class BillingService {
    public static final String EVENT_SOURCE = "urn:baobab-platform:service:baobab-subscriptions";
    static final String CREATED = "com.baobab-platform.subscriptions.billing-subscription.created.v1";
    static final String SUSPENDED = "com.baobab-platform.subscriptions.billing-subscription.suspended.v1";
    static final String CANCELLED = "com.baobab-platform.subscriptions.billing-subscription.cancelled.v1";
    static final String USAGE_RECORDED = "com.baobab-platform.subscriptions.usage.recorded.v1";
    private static final Map<String, String> PAYLOAD_DEFS = Map.of(CREATED, "BillingSubscriptionCreated",
            SUSPENDED, "BillingSubscriptionSuspended", CANCELLED, "BillingSubscriptionCancelled", USAGE_RECORDED, "UsageRecorded");
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

    record EnsureRequest(String tenantId, String productSubscriptionId, String productId, String platformAccountId,
            String legalEntityId, SubscriptionType subscriptionType, Classification classification) {
    }

    record CommandRequest(String tenantId, String reason) {
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

    // --- Operations ---------------------------------------------------------

    /** Ensures the billing projection of one classified ProductSubscription. */
    public Result ensure(String idempotencyKey, byte[] body, String correlationId) {
        JsonNode node = parse(body);
        EnsureRequest req = decode(node, "EnsureBillingProjectionRequest", EnsureRequest.class);
        BillingPolicy policy = policies.forType(req.subscriptionType());
        return idempotent(req.tenantId(), "ensure", idempotencyKey, node, tx -> {
            var existing = tx.projectionForProductSubscription(req.tenantId(), req.productSubscriptionId());
            Instant at = now();
            if (existing.isEmpty()) {
                String id = Ids.billingSubscriptionId();
                Projection draft = new Projection(id, req.tenantId(), req.productSubscriptionId(), req.productId(),
                        req.platformAccountId(), req.legalEntityId(), req.subscriptionType(), req.classification(),
                        AppliedPolicy.of(policy), BillingState.PENDING_CONFIGURATION, Readiness.of(List.of()),
                        new ProviderRef(provider.kind(), provider.simulated(), null), 1, at, at);
                String reference = provider.ensureSubscription(draft);
                Projection created = settle(draft, policy, new ProviderRef(provider.kind(), provider.simulated(), reference), false, false);
                tx.insertProjection(created);
                ObjectNode data = Json.mapper().createObjectNode()
                        .put("billing_subscription_id", id)
                        .put("product_subscription_id", created.productSubscriptionId())
                        .put("tenant_id", created.tenantId())
                        .put("subscription_type", created.subscriptionType().name())
                        .put("billing_required", created.billingPolicy().billingRequired())
                        .put("billing_state", created.billingState().name())
                        .put("simulated", created.provider().simulated())
                        .put("created_at", at.toString());
                tx.appendEvent(event(CREATED, created, data, correlationId, idempotencyKey, at));
                return new Result(201, Json.mapper().valueToTree(created), false);
            }
            Projection current = existing.get();
            if (current.billingState() == BillingState.CANCELLED) {
                throw new BillingException(409, "BILLING_PROJECTION_CANCELLED", "the billing projection is cancelled", false);
            }
            if (!current.productId().equals(req.productId())) {
                throw new BillingException(409, "PRODUCT_MISMATCH", "the product subscription is billed for another product", false);
            }
            if (req.classification().classifiedAt().isBefore(current.classification().classifiedAt())) {
                throw new BillingException(409, "STALE_CLASSIFICATION",
                        "the projection already records a newer classification", false);
            }
            if (sameTerms(current, req)) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            // A reclassification or a changed commercial context: update in place, never a new identity.
            Projection changed = new Projection(current.billingSubscriptionId(), current.tenantId(), current.productSubscriptionId(),
                    current.productId(), req.platformAccountId(), req.legalEntityId(), req.subscriptionType(), req.classification(),
                    AppliedPolicy.of(policy), current.billingState(), current.readiness(), current.provider(),
                    current.version() + 1, current.createdAt(), at);
            Projection updated = settle(changed, policy, current.provider(), current.billingState() == BillingState.SUSPENDED, false);
            tx.updateProjection(updated, current.version());
            return new Result(200, Json.mapper().valueToTree(updated), false);
        });
    }

    private static boolean sameTerms(Projection p, EnsureRequest req) {
        return p.subscriptionType() == req.subscriptionType() && p.classification().equals(req.classification())
                && Objects.equals(p.platformAccountId(), req.platformAccountId())
                && Objects.equals(p.legalEntityId(), req.legalEntityId());
    }

    /**
     * The state and readiness the policy and provider allow. Only billing that
     * requires money consults the provider's reality and the payments port.
     */
    private Projection settle(Projection p, BillingPolicy policy, ProviderRef providerRef, boolean suspended, boolean cancelled) {
        BillingState state;
        List<ReadinessReason> reasons = new ArrayList<>();
        if (cancelled) {
            state = BillingState.CANCELLED;
            reasons.add(ReadinessReason.PROJECTION_CANCELLED);
        } else {
            List<ReadinessReason> blockers = new ArrayList<>();
            if (policy.billingRequired()) {
                if (providerRef.simulated()) {
                    blockers.add(ReadinessReason.BILLING_PROVIDER_NOT_CONFIGURED);
                }
                if (policy.mayRequirePayment() && !payments.configured()) {
                    blockers.add(ReadinessReason.PAYMENT_PROVIDER_NOT_CONFIGURED);
                }
            }
            if (suspended) {
                state = BillingState.SUSPENDED;
                reasons.add(ReadinessReason.PROJECTION_SUSPENDED);
                reasons.addAll(blockers);
            } else {
                state = blockers.isEmpty() ? BillingState.ACTIVE : BillingState.PENDING_CONFIGURATION;
                reasons.addAll(blockers);
            }
        }
        return new Projection(p.billingSubscriptionId(), p.tenantId(), p.productSubscriptionId(), p.productId(),
                p.platformAccountId(), p.legalEntityId(), p.subscriptionType(), p.classification(), p.billingPolicy(), state,
                Readiness.of(reasons), providerRef, p.version(), p.createdAt(), p.updatedAt());
    }

    /** Suspends a projection by a governed command. */
    public Result suspend(String idempotencyKey, String billingSubscriptionId, byte[] body, String correlationId) {
        JsonNode node = parse(body);
        CommandRequest req = decode(node, "BillingProjectionCommand", CommandRequest.class);
        return idempotent(req.tenantId(), "suspend:" + billingSubscriptionId, idempotencyKey, node, tx -> {
            Projection current = tx.projection(req.tenantId(), billingSubscriptionId).orElseThrow(BillingException::notFound);
            if (current.billingState() == BillingState.CANCELLED) {
                throw new BillingException(409, "BILLING_PROJECTION_CANCELLED", "the billing projection is cancelled", false);
            }
            if (current.billingState() == BillingState.SUSPENDED) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            provider.suspend(current);
            Instant at = now();
            Projection suspended = settle(bump(current, at), policies.forType(current.subscriptionType()), current.provider(), true, false);
            tx.updateProjection(suspended, current.version());
            tx.appendEvent(event(SUSPENDED, suspended, Json.mapper().createObjectNode()
                    .put("billing_subscription_id", suspended.billingSubscriptionId())
                    .put("product_subscription_id", suspended.productSubscriptionId())
                    .put("tenant_id", suspended.tenantId())
                    .put("suspended_at", at.toString()), correlationId, idempotencyKey, at));
            return new Result(200, Json.mapper().valueToTree(suspended), false);
        });
    }

    /** Cancels a projection by a governed command. Cancellation is final. */
    public Result cancel(String idempotencyKey, String billingSubscriptionId, byte[] body, String correlationId) {
        JsonNode node = parse(body);
        CommandRequest req = decode(node, "BillingProjectionCommand", CommandRequest.class);
        return idempotent(req.tenantId(), "cancel:" + billingSubscriptionId, idempotencyKey, node, tx -> {
            Projection current = tx.projection(req.tenantId(), billingSubscriptionId).orElseThrow(BillingException::notFound);
            if (current.billingState() == BillingState.CANCELLED) {
                return new Result(200, Json.mapper().valueToTree(current), false);
            }
            provider.cancel(current);
            Instant at = now();
            Projection cancelled = settle(bump(current, at), policies.forType(current.subscriptionType()), current.provider(), false, true);
            tx.updateProjection(cancelled, current.version());
            tx.appendEvent(event(CANCELLED, cancelled, Json.mapper().createObjectNode()
                    .put("billing_subscription_id", cancelled.billingSubscriptionId())
                    .put("product_subscription_id", cancelled.productSubscriptionId())
                    .put("tenant_id", cancelled.tenantId())
                    .put("cancelled_at", at.toString()), correlationId, idempotencyKey, at));
            return new Result(200, Json.mapper().valueToTree(cancelled), false);
        });
    }

    private static Projection bump(Projection p, Instant at) {
        return new Projection(p.billingSubscriptionId(), p.tenantId(), p.productSubscriptionId(), p.productId(),
                p.platformAccountId(), p.legalEntityId(), p.subscriptionType(), p.classification(), p.billingPolicy(),
                p.billingState(), p.readiness(), p.provider(), p.version() + 1, p.createdAt(), at);
    }

    /** Meters usage. Every type is metered; usage is billable only where the policy charges money. */
    public Result recordUsage(String idempotencyKey, String billingSubscriptionId, byte[] body, String correlationId) {
        JsonNode node = parse(body);
        UsageRequest req = decode(node, "RecordUsageRequest", UsageRequest.class);
        return idempotent(req.tenantId(), "usage:" + billingSubscriptionId, idempotencyKey, node, tx -> {
            Projection projection = tx.projection(req.tenantId(), billingSubscriptionId).orElseThrow(BillingException::notFound);
            if (projection.billingState() == BillingState.CANCELLED) {
                throw new BillingException(409, "BILLING_PROJECTION_CANCELLED", "the billing projection is cancelled", false);
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
                    .put("occurred_at", usage.occurredAt().toString()), correlationId, idempotencyKey, at));
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

    // --- Idempotency and events --------------------------------------------

    private Result idempotent(String tenantId, String operation, String key, JsonNode request,
            Function<BillingStore.Tx, Result> work) {
        String requestHash = hash(request);
        for (int attempt = 1; ; attempt++) {
            try {
                return store.transact(tx -> {
                    var prior = tx.idempotency(tenantId, operation, key);
                    if (prior.isPresent()) {
                        if (!prior.get().requestHash().equals(requestHash)) {
                            throw new BillingException(409, "IDEMPOTENCY_KEY_REUSED",
                                    "the Idempotency-Key was used for a different request", false);
                        }
                        return new Result(prior.get().status(), readStored(prior.get().responseBody()), true);
                    }
                    Result result = work.apply(tx);
                    tx.saveIdempotency(new IdempotencyRecord(tenantId, operation, key, requestHash, result.status(),
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

    private static OutboxEvent event(String type, Projection subject, ObjectNode data, String correlationId, String idempotencyKey,
            Instant at) {
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
                .put("correlationid", correlationId)
                .put("tenantid", subject.tenantId())
                .put("idempotencykey", idempotencyKey);
        envelope.set("data", data);
        return new OutboxEvent(id, type, subject.tenantId(), subject.billingSubscriptionId(), envelope.toString(), at);
    }
}
