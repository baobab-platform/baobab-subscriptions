package com.baobabplatform.subscriptions.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable, provider-neutral identity for a future outbound billing operation.
 * The adapter may only execute an authorised, persisted intent, never infer
 * classification or monetary treatment from provider state (ADR-SUB-0015).
 *
 * <p>This is a scaffold: constructing an intent performs no provider IO.
 */
public record ProviderOperationIntent(
        UUID operationId,
        String tenantId,
        String billingSubscriptionId,
        long authoritativeRevision,
        String operationKind,
        String requestSha256) {

    public ProviderOperationIntent {
        Objects.requireNonNull(operationId, "operationId");
        requireText(tenantId, "tenantId");
        requireText(billingSubscriptionId, "billingSubscriptionId");
        requireText(operationKind, "operationKind");
        if (authoritativeRevision < 1) {
            throw new IllegalArgumentException("authoritativeRevision must be positive");
        }
        if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestSha256 must be a lowercase SHA-256 digest");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
