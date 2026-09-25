package com.baobabplatform.subscriptions.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** One metered usage observation (subscriptions/v1 UsageRecord). Billable only where the policy charges. */
public record UsageRecord(
        String usageRecordId,
        String billingSubscriptionId,
        String productSubscriptionId,
        String tenantId,
        String metricKey,
        BigDecimal quantity,
        String unit,
        Instant occurredAt,
        Instant recordedAt,
        String sourceReference,
        boolean billable) {
}
