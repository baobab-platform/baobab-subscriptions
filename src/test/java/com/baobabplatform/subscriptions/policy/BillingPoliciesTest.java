package com.baobabplatform.subscriptions.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.domain.SubscriptionType;
import org.junit.jupiter.api.Test;

class BillingPoliciesTest {
    @Test
    void internalIsZeroChargeMeteredAndNeverPaid() {
        BillingPolicy internal = BillingPolicies.load().forType(SubscriptionType.INTERNAL);
        assertEquals("ZERO", internal.monetaryCharge());
        assertFalse(internal.billingRequired());
        assertEquals("NEVER", internal.paymentExecution());
        assertTrue(internal.usageMetering() && internal.entitlementControl() && internal.audit()
                && internal.readinessControl() && internal.isolationControl());
    }

    @Test
    void everyTypeHasAPolicy() {
        BillingPolicies policies = BillingPolicies.load();
        for (SubscriptionType type : SubscriptionType.values()) {
            BillingPolicy p = policies.forType(type);
            assertEquals(p.chargesMoney(), p.billingRequired(), type + ": billing is required exactly when money is charged");
        }
        assertEquals("REQUIRED", policies.forType(SubscriptionType.COMMERCIAL).paymentExecution());
    }
}
