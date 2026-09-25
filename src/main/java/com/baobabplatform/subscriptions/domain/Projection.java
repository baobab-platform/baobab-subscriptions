package com.baobabplatform.subscriptions.domain;

import java.time.Instant;

/**
 * How this engine bills one Control Plane ProductSubscription
 * (subscriptions/v1 BillingProjection). It references the subscription and its
 * classification; it never redefines them.
 */
public record Projection(
        String billingSubscriptionId,
        String tenantId,
        String productSubscriptionId,
        String productId,
        String platformAccountId,
        String legalEntityId,
        SubscriptionType subscriptionType,
        Classification classification,
        AppliedPolicy billingPolicy,
        BillingState billingState,
        Readiness readiness,
        ProviderRef provider,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
