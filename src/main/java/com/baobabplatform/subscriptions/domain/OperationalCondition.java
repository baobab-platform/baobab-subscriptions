package com.baobabplatform.subscriptions.domain;

/** Operational condition, separate from the business lifecycle (ADR-SUB-0003 section 11). */
public enum OperationalCondition {
    HEALTHY, DEGRADED, RECONCILIATION_REQUIRED, RETRYING, BLOCKED
}
