package com.baobabplatform.subscriptions.provider;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;

/**
 * The implementation-neutral billing port (ADR-SUB-0001 section 6). The Kill
 * Bill adapter will implement it; until then only the temporary provider does.
 */
public interface BillingProvider {
    /** subscriptions/v1 billingProviderKind. */
    String kind();

    /** True when the provider represents no real billing account and moves no money. */
    boolean simulated();

    /** Establishes the provider side of a projection. Returns an opaque provider reference, or null. */
    String ensureSubscription(Projection projection);

    void suspend(Projection projection);

    void cancel(Projection projection);

    void recordUsage(Projection projection, UsageRecord usage);

    boolean healthy();
}
