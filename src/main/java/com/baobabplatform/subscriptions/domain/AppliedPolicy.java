package com.baobabplatform.subscriptions.domain;

import com.baobabplatform.subscriptions.policy.BillingPolicy;

/** The billing policy as a projection applies it (subscriptions/v1 billingPolicy). */
public record AppliedPolicy(String monetaryCharge, boolean billingRequired, boolean usageMetering, String paymentExecution) {
    public static AppliedPolicy of(BillingPolicy policy) {
        return new AppliedPolicy(policy.monetaryCharge(), policy.billingRequired(), policy.usageMetering(),
                policy.paymentExecution());
    }

    public boolean chargesMoney() {
        return "PRICED".equals(monetaryCharge);
    }
}
