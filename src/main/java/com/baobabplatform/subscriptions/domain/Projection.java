package com.baobabplatform.subscriptions.domain;

import java.time.Instant;

/**
 * How this engine bills one Control Plane ProductSubscription
 * (subscriptions/v1 BillingProjection). It references the subscription and its
 * classification; it never redefines them. authoritativeRevision is the
 * Control Plane revision the projection reflects: an older one never
 * regresses it (ADR-SUB-0003 section 16).
 */
public record Projection(
        String billingSubscriptionId,
        String tenantId,
        String productSubscriptionId,
        long authoritativeRevision,
        String productId,
        String platformAccountId,
        String legalEntityId,
        SubscriptionType subscriptionType,
        Classification classification,
        AppliedPolicy billingPolicy,
        BillingState billingState,
        OperationalCondition operationalCondition,
        Readiness readiness,
        ProviderRef provider,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** A copy with a new lifecycle outcome. */
    public Projection with(BillingState state, Readiness readiness, long revision, long version, Instant updatedAt) {
        return new Projection(billingSubscriptionId, tenantId, productSubscriptionId, revision, productId, platformAccountId,
                legalEntityId, subscriptionType, classification, billingPolicy, state, operationalCondition, readiness, provider,
                version, createdAt, updatedAt);
    }
}
