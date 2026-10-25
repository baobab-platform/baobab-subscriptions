package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.provider.ProviderOperationIntent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable intent / delivery uncertainty independent of provider-native state.
 *
 * <p>SENT means the dispatch lease was durably acquired, not proof that the
 * remote server executed the command. An expired SENT lease is UNKNOWN, never
 * automatically eligible for a second monetary dispatch (ADR-SUB-0015).
 */
public record ProviderOperation(
        UUID operationId, String tenantId, String billingSubscriptionId,
        String providerKind, String operationKind, String idempotencyKey,
        String requestSha256, long authoritativeRevision, State state,
        String leaseOwner, long leaseEpoch, Instant leaseExpiresAt,
        String reasonCode, Instant createdAt, Instant updatedAt) {

    public enum State {
        PREPARED, SUPERSEDED, SENT, CONVERGED, ALREADY_CONVERGED, RETRYABLE_FAILURE,
        PERMANENT_FAILURE, CONFLICT, UNKNOWN_OUTCOME, RECONCILED
    }

    public ProviderOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        for (String s : new String[]{tenantId, billingSubscriptionId, providerKind,
                operationKind, idempotencyKey}) {
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException("provider operation identity must be nonempty");
            }
        }
        if ("TEMPORARY".equals(providerKind)) {
            throw new IllegalArgumentException("a simulated provider never owns monetary custody");
        }
        if (authoritativeRevision < 1 || leaseEpoch < 0) {
            throw new IllegalArgumentException("invalid revision or lease epoch");
        }
        if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid request SHA-256");
        }
        if ((leaseOwner == null) != (leaseExpiresAt == null)) {
            throw new IllegalArgumentException("lease owner and deadline must be paired");
        }
        if (leaseOwner != null && leaseOwner.isBlank()) {
            throw new IllegalArgumentException("lease owner cannot be blank");
        }
        if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("invalid provider reason code");
        }
    }

    public static ProviderOperation prepared(
            ProviderOperationIntent intent, String providerKind, String idempotencyKey, Instant now) {
        return new ProviderOperation(intent.operationId(), intent.tenantId(),
                intent.billingSubscriptionId(), providerKind, intent.operationKind(),
                idempotencyKey, intent.requestSha256(), intent.authoritativeRevision(),
                State.PREPARED, null, 0, null, null, now, now);
    }

    public ProviderOperation change(State next, String owner, long epoch,
                                    Instant expiresAt, String reason, Instant now) {
        return new ProviderOperation(operationId, tenantId, billingSubscriptionId,
                providerKind, operationKind, idempotencyKey, requestSha256,
                authoritativeRevision, next, owner, epoch, expiresAt,
                reason, createdAt, now);
    }

    public boolean leaseCurrent(String owner, long epoch, Instant now) {
        return leaseOwner != null && leaseOwner.equals(owner) && leaseEpoch == epoch
                && leaseExpiresAt.isAfter(now);
    }
}
