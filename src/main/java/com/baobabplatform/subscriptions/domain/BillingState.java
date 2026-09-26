package com.baobabplatform.subscriptions.domain;

/**
 * The billing projection's business lifecycle (ADR-SUB-0003 section 5;
 * subscriptions/v1 billingState). Infrastructure trouble is never a billing
 * state: it is an {@link OperationalCondition}.
 */
public enum BillingState {
    PENDING_CONFIGURATION, PROVISIONING, ACTIVE, SUSPENDED, TERMINATING, TERMINATED;

    public boolean terminal() {
        return this == TERMINATED;
    }
}
