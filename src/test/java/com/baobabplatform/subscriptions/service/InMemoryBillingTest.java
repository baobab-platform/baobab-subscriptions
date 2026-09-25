package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;

class InMemoryBillingTest extends BillingScenarios {
    @Override
    protected BillingStore newStore() {
        return new InMemoryBillingStore();
    }
}
