package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.ProviderRef;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.provider.ProviderOperationIntent;
import com.baobabplatform.subscriptions.provider.ProviderOutcome;
import com.baobabplatform.subscriptions.store.AuditRecord;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.ProviderMapping;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-SUB-0003/0015: durable billing-provider operation preparation, leased
 * dispatch custody and read-only recovery after an ambiguous network result.
 *
 * <p>This component has NO scheduler or external HTTP endpoint. The future
 * provider integration must first wire IAM/CP-governed execution and obtain a
 * verified billing account, pricing and provider capability. In particular,
 * claimForDispatch only acquires a durable token; it DOES NOT call Kill Bill.
 * Production configuration still refuses a simulated or absent provider.
 */
public final class ProviderReconciliationService {
    private final BillingStore store;
    private final BillingProvider provider;
    private final Clock clock;

    public ProviderReconciliationService(BillingStore store, BillingProvider provider, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void verifyProvider() {
        if (provider.simulated() || "TEMPORARY".equals(provider.kind())) {
            throw new IllegalStateException("simulated provider cannot obtain a monetary operation lease");
        }
    }

    private static void checkWorker(String worker, Duration duration) {
        if (worker == null || !worker.matches("[A-Za-z0-9._:-]{3,120}")) {
            throw new IllegalArgumentException("a named bounded workload worker is required");
        }
        if (duration == null || duration.isNegative() || duration.isZero()
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("lease must be positive and no longer than 5 minutes");
        }
    }

    private Projection verifyCurrent(BillingStore.Tx tx, ProviderOperation op) {
        Projection p = tx.projection(op.tenantId(), op.billingSubscriptionId())
                .orElseThrow(() -> new IllegalStateException("current tenant projection missing"));
        if (p.authoritativeRevision() != op.authoritativeRevision()
                || !p.billingPolicy().billingRequired()
                || !p.provider().kind().equals(op.providerKind())
                || p.provider().simulated() || !provider.kind().equals(op.providerKind())
                || p.billingState().terminal()) {
            throw new IllegalStateException("provider operation no longer has current governed billing context");
        }
        return p;
    }

    /**
     * Record monetary provider intent in the SAME transaction that persists
     * the CP-governed projection. Never dispatch from this transaction.
     */
    void prepare(BillingStore.Tx tx, Projection projection, String operationKind,
                 String idempotencyKey, String requestSha256, Instant at) {
        verifyProvider();
        if (projection.provider().simulated() || !projection.billingPolicy().billingRequired()) {
            throw new IllegalArgumentException("a provider operation requires priced governed context");
        }
        var prior = tx.providerOperationForKey(projection.tenantId(), provider.kind(), operationKind, idempotencyKey);
        if (prior.isPresent()) {
            ProviderOperation op = prior.get();
            if (!op.billingSubscriptionId().equals(projection.billingSubscriptionId())
                    || !op.requestSha256().equals(requestSha256)
                    || op.authoritativeRevision() != projection.authoritativeRevision()) {
                throw new IllegalStateException("provider idempotency identity has changed");
            }
            return;
        }
        ProviderOperationIntent intent = new ProviderOperationIntent(UUID.randomUUID(),
                projection.tenantId(), projection.billingSubscriptionId(), projection.authoritativeRevision(),
                operationKind, requestSha256);
        ProviderOperation op = ProviderOperation.prepared(intent, provider.kind(), idempotencyKey, at);
        tx.insertProviderOperation(op);
        tx.appendAudit(audit(op, projection, "provider_operation.prepared", null,
                op.state().name(), "PROVIDER_DISPATCH_NOT_ENABLED", "baobab-subscriptions", at));
    }

    /** Read only within a single tenant: foreign operation IDs return empty. */
    public Optional<ProviderOperation> find(String tenantId, UUID operationId) {
        return store.transact(tx -> tx.providerOperation(tenantId, operationId));
    }

    /**
     * Persist SENT before any future external dispatch. This lease is never
     * acquired a second time after SENT, even after an expiry or crash.
     */
    public ProviderOperation claimForDispatch(String tenantId, UUID id, String worker, Duration lease) {
        verifyProvider();
        checkWorker(worker, lease);
        return store.transact(tx -> {
            ProviderOperation prior = tx.providerOperation(tenantId, id)
                    .orElseThrow(() -> new IllegalArgumentException("provider operation not found"));
            Projection projection = verifyCurrent(tx, prior);
            if (prior.state() != ProviderOperation.State.PREPARED) {
                throw new IllegalStateException("a dispatched or uncertain operation cannot be re-dispatched");
            }
            Instant at = now();
            ProviderOperation claimed = prior.change(ProviderOperation.State.SENT,
                    worker, prior.leaseEpoch() + 1, at.plus(lease), null, at);
            tx.updateProviderOperation(claimed, prior.state(), prior.leaseEpoch());
            tx.appendAudit(audit(claimed, projection, "provider_operation.dispatch_claimed",
                    prior.state().name(), claimed.state().name(), "DISPATCH_LEASE_ACQUIRED", worker, at));
            return claimed;
        });
    }

    /**
     * Only the live owner/epoch may commit a provider result. An expired lease
     * cannot assert successful delivery: recover through observation instead.
     */
    public ProviderOperation recordDispatchOutcome(
            String tenantId, UUID id, String worker, long epoch, ProviderOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        verifyProvider();
        return store.transact(tx -> {
            ProviderOperation prior = tx.providerOperation(tenantId, id)
                    .orElseThrow(() -> new IllegalArgumentException("provider operation not found"));
            Projection projection = verifyCurrent(tx, prior);
            Instant at = now();
            if (prior.state() != ProviderOperation.State.SENT || !prior.leaseCurrent(worker, epoch, at)) {
                throw new IllegalStateException("dispatch lease is stale, expired or does not match its owner");
            }
            ProviderOperation.State state = switch (outcome.status()) {
                case CONVERGED -> ProviderOperation.State.CONVERGED;
                case ALREADY_CONVERGED -> ProviderOperation.State.ALREADY_CONVERGED;
                case RETRYABLE_FAILURE -> ProviderOperation.State.RETRYABLE_FAILURE;
                case PERMANENT_FAILURE -> ProviderOperation.State.PERMANENT_FAILURE;
                case CONFLICT -> ProviderOperation.State.CONFLICT;
                case UNKNOWN_OUTCOME -> ProviderOperation.State.UNKNOWN_OUTCOME;
            };
            if (outcome.isConverged()) {
                if (outcome.providerReference() == null) {
                    state = ProviderOperation.State.UNKNOWN_OUTCOME;
                } else {
                    bindObservedReference(tx, projection, prior, outcome.providerReference(), at);
                }
            }
            String reason = outcome.isConverged() && outcome.providerReference() == null
                    ? "MISSING_PROVIDER_REFERENCE" : outcome.reasonCode();
            ProviderOperation next = prior.change(state, null, prior.leaseEpoch(), null, reason, at);
            tx.updateProviderOperation(next, prior.state(), prior.leaseEpoch());
            tx.appendAudit(audit(next, projection, "provider_operation.dispatch_outcome",
                    prior.state().name(), next.state().name(), reason, worker, at));
            return next;
        });
    }

    /**
     * Read-only provider observation after an unknown outcome. Even if the
     * original dispatch worker crashed, a new observation worker can acquire
     * a fresh lease epoch; no new monetary mutation is ever sent.
     */
    public ProviderOperation reconcile(String tenantId, UUID id, String worker, Duration lease) {
        verifyProvider();
        checkWorker(worker, lease);
        ProviderOperation claimed = store.transact(tx -> {
            ProviderOperation prior = tx.providerOperation(tenantId, id)
                    .orElseThrow(() -> new IllegalArgumentException("provider operation not found"));
            Projection projection = verifyCurrent(tx, prior);
            Instant at = now();
            boolean expiredSent = prior.state() == ProviderOperation.State.SENT
                    && prior.leaseExpiresAt() != null && !prior.leaseExpiresAt().isAfter(at);
            boolean reclaimObservation = prior.state() == ProviderOperation.State.UNKNOWN_OUTCOME
                    && (prior.leaseExpiresAt() == null || !prior.leaseExpiresAt().isAfter(at));
            if (!expiredSent && !reclaimObservation) {
                throw new IllegalStateException("only uncertain or expired dispatched operations can be observed");
            }
            ProviderOperation next = prior.change(ProviderOperation.State.UNKNOWN_OUTCOME,
                    worker, prior.leaseEpoch() + 1, at.plus(lease), "OBSERVATION_PENDING", at);
            tx.updateProviderOperation(next, prior.state(), prior.leaseEpoch());
            tx.appendAudit(audit(next, projection, "provider_operation.observation_claimed",
                    prior.state().name(), next.state().name(), "OBSERVATION_PENDING", worker, at));
            return next;
        });

        // Absolutely no network IO while the database transaction is held.
        ProviderOutcome observed;
        if (!provider.capabilities().subscriptionObservation()) {
            observed = ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "OBSERVATION_NOT_SUPPORTED");
        } else {
            try {
                Projection projection = store.transact(tx -> verifyCurrent(tx, claimed));
                observed = provider.observeSubscription(projection,
                        new ProviderOperationIntent(claimed.operationId(), claimed.tenantId(),
                                claimed.billingSubscriptionId(), claimed.authoritativeRevision(),
                                claimed.operationKind(), claimed.requestSha256()));
                if (observed == null) {
                    observed = ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "EMPTY_PROVIDER_OBSERVATION");
                }
            } catch (RuntimeException outage) {
                observed = ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_OBSERVATION_UNAVAILABLE");
            }
        }
        final ProviderOutcome outcome = observed;
        return store.transact(tx -> {
            ProviderOperation prior = tx.providerOperation(tenantId, id)
                    .orElseThrow(() -> new IllegalStateException("provider operation disappeared"));
            Projection projection = verifyCurrent(tx, prior);
            Instant at = now();
            if (prior.state() != ProviderOperation.State.UNKNOWN_OUTCOME
                    || !prior.leaseCurrent(worker, claimed.leaseEpoch(), at)) {
                throw new IllegalStateException("observation lease changed; stale observation was rejected");
            }
            // A lack of a confirmed resource ID cannot clear ambiguity.
            boolean resolved = outcome.isConverged() && outcome.providerReference() != null;
            if (resolved) {
                bindObservedReference(tx, projection, prior, outcome.providerReference(), at);
            }
            ProviderOperation next = prior.change(resolved ? ProviderOperation.State.RECONCILED
                            : ProviderOperation.State.UNKNOWN_OUTCOME,
                    null, prior.leaseEpoch(), null,
                    resolved ? null : outcome.reasonCode() == null ? "MISSING_PROVIDER_REFERENCE"
                            : outcome.reasonCode(), at);
            tx.updateProviderOperation(next, prior.state(), prior.leaseEpoch());
            tx.appendAudit(audit(next, projection, "provider_operation.observation_result",
                    prior.state().name(), next.state().name(), next.reasonCode(), worker, at));
            return next;
        });
    }

    /**
     * Mapping + projection reference converge atomically. A different native
     * resource ID is never silently substituted (duplicate billing hazard).
     */
    private static void bindObservedReference(BillingStore.Tx tx, Projection p,
                                               ProviderOperation op, String providerId, Instant at) {
        ProviderMapping mapping = tx.providerMapping(op.tenantId(), op.providerKind(), op.billingSubscriptionId())
                .orElse(null);
        if (mapping != null && !mapping.providerResourceId().equals(providerId)) {
            throw new IllegalStateException("provider reference conflict requires independent resolution");
        }
        if (p.provider().providerReference() != null
                && !p.provider().providerReference().equals(providerId)) {
            throw new IllegalStateException("projection provider reference conflict");
        }
        if (mapping == null) {
            tx.insertProviderMapping(new ProviderMapping(UUID.randomUUID(),
                    op.tenantId(), op.providerKind(), op.billingSubscriptionId(), providerId, at, at));
        }
        if (p.provider().providerReference() == null) {
            // No financial readiness assertion: pricing, billing-account and
            // payment-path qualification remain independent blocked gates.
            Projection updated = new Projection(p.billingSubscriptionId(), p.tenantId(),
                    p.productSubscriptionId(), p.authoritativeRevision(), p.productId(),
                    p.platformAccountId(), p.legalEntityId(), p.subscriptionType(),
                    p.classification(), p.billingPolicy(), p.billingState(),
                    p.operationalCondition(), p.readiness(),
                    new ProviderRef(op.providerKind(), false, providerId),
                    p.version() + 1, p.createdAt(), at);
            tx.updateProjection(updated, p.version());
        }
    }

    private static AuditRecord audit(ProviderOperation op, Projection projection, String action,
                                     String before, String after, String reason, String worker, Instant at) {
        return new AuditRecord(UUID.randomUUID(), at, "WORKLOAD", worker, worker,
                op.tenantId(), projection.platformAccountId(), "provider_operation",
                op.operationId().toString(), action, before, after, reason,
                op.idempotencyKey(), null, op.authoritativeRevision(), 1,
                projection.provider().providerReference(), after);
    }
}
