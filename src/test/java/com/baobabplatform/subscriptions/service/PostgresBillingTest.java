package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import org.junit.jupiter.api.AfterEach;

/** The same scenarios against PostgreSQL. Skipped unless TEST_DATABASE_URL (a jdbc:postgresql:// URL) is set. */
class PostgresBillingTest extends BillingScenarios {
    private final String run = Long.toString(System.nanoTime(), 36);

    @Override
    protected BillingStore newStore() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"), "TEST_DATABASE_URL not set; skipping PostgreSQL tests");
        return new PostgresBillingStore(url, System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Override
    protected String unique() {
        return run + Integer.toHexString(System.identityHashCode(this));
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }
}
