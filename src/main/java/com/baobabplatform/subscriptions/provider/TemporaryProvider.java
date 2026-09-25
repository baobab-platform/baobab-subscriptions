package com.baobabplatform.subscriptions.provider;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;

/**
 * The deterministic development/integration provider (ADR-SUB-0001 section 6).
 * It keeps no account, contacts nothing, never represents money movement and
 * marks every projection simulated. It is refused in production.
 */
public final class TemporaryProvider implements BillingProvider {
    @Override
    public String kind() {
        return "TEMPORARY";
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public String ensureSubscription(Projection projection) {
        return null;
    }

    @Override
    public void suspend(Projection projection) {
        // Nothing is held provider-side.
    }

    @Override
    public void cancel(Projection projection) {
        // Nothing is held provider-side.
    }

    @Override
    public void recordUsage(Projection projection, UsageRecord usage) {
        // Usage is metered in this engine's own store.
    }

    @Override
    public boolean healthy() {
        return true;
    }
}
