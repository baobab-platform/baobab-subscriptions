package com.baobabplatform.subscriptions.domain;

/** subscriptions/v1 readinessReason: a precise blocker, preferred to fake readiness. */
public enum ReadinessReason {
    BILLING_PROVIDER_NOT_CONFIGURED,
    PAYMENT_PROVIDER_NOT_CONFIGURED,
    PAYMENT_METHOD_REQUIRED,
    USAGE_METERING_UNAVAILABLE,
    PROJECTION_SUSPENDED,
    PROJECTION_CANCELLED
}
