package com.baobabplatform.subscriptions.policy;

/**
 * One billing-policy.yaml entry: what a subscription type means for billing.
 * Only the monetary charge and payment execution vary; metering, entitlement,
 * audit, readiness and isolation are always on.
 */
public record BillingPolicy(
        String monetaryCharge,
        boolean billingRequired,
        boolean usageMetering,
        boolean entitlementControl,
        boolean audit,
        boolean readinessControl,
        boolean isolationControl,
        String paymentExecution) {

    public boolean chargesMoney() {
        return "PRICED".equals(monetaryCharge);
    }

    /** Payment execution may be needed at all. NEVER means the payments engine is never invoked. */
    public boolean mayRequirePayment() {
        return "REQUIRED".equals(paymentExecution);
    }
}
