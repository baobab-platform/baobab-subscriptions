package com.baobabplatform.subscriptions.domain;

import java.util.List;

/** READY with no reasons, or BLOCKED with at least one. */
public record Readiness(String status, List<ReadinessReason> reasons) {
    public Readiness {
        reasons = List.copyOf(reasons);
        if (status.equals("READY") != reasons.isEmpty()) {
            throw new IllegalArgumentException("READY has no reasons and BLOCKED has at least one");
        }
    }

    public static Readiness of(List<ReadinessReason> reasons) {
        return new Readiness(reasons.isEmpty() ? "READY" : "BLOCKED", reasons);
    }
}
