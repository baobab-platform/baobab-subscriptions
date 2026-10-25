package com.baobabplatform.subscriptions.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Baobab-owned reference to a provider resource; never a canonical identity
 * and never subscription classification authority (ADR-SUB-0015 §14-18).
 */
public record ProviderMapping(
        UUID mappingId, String tenantId, String providerKind,
        String billingSubscriptionId, String providerResourceId,
        Instant createdAt, Instant lastVerifiedAt) {
    public ProviderMapping {
        Objects.requireNonNull(mappingId, "mappingId");
        Objects.requireNonNull(createdAt, "createdAt");
        for (String s : new String[]{tenantId, providerKind, billingSubscriptionId, providerResourceId}) {
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException("mapping identifiers must not be blank");
            }
        }
        if ("TEMPORARY".equals(providerKind)) {
            throw new IllegalArgumentException("temporary provider cannot own mappings");
        }
    }
}
