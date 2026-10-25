package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.domain.Classification;
import com.baobabplatform.subscriptions.domain.SubscriptionType;

/** CP snapshot consistency, separate from INTERNAL eligibility and financial grants. */
@FunctionalInterface
public interface CurrentProjectionAuthority {
    boolean current(String tenant, String subscription, long revision, String product,
                    SubscriptionType type, Classification classification);
}
