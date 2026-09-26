package com.baobabplatform.subscriptions.domain;

import java.util.List;

/** READY with no blockers, or BLOCKED with at least one. */
public record Readiness(String status, ReadinessFacts facts, List<BillingBlocker> blockers) {
    public Readiness {
        blockers = List.copyOf(blockers);
        if (status.equals("READY") != blockers.isEmpty()) {
            throw new IllegalArgumentException("READY has no blockers and BLOCKED has at least one");
        }
    }

    public static Readiness of(ReadinessFacts facts, List<BillingBlocker> blockers) {
        return new Readiness(blockers.isEmpty() ? "READY" : "BLOCKED", facts, blockers);
    }
}
