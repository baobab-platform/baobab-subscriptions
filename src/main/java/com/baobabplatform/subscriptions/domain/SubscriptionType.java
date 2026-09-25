package com.baobabplatform.subscriptions.domain;

/** The Control Plane's subscription classification (ADR-BCP-005). The engine records it; it never decides it. */
public enum SubscriptionType {
    COMMERCIAL, INTERNAL, TRIAL, PARTNER, MANUAL, MIGRATION
}
