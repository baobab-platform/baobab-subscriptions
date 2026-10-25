package com.baobabplatform.subscriptions.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fail-closed rules required before wiring a real Kill Bill provider. */
class ProviderFoundationTest {
    @Test
    void unknownOutcomesCannotBeMistakenForSuccessOrBlindlyRetried() {
        var unknown = ProviderOutcome.failure(ProviderOutcome.Status.UNKNOWN_OUTCOME, "PROVIDER_TIMEOUT");
        assertFalse(unknown.isConverged());
        assertTrue(unknown.requiresReconciliation());
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderOutcome(ProviderOutcome.Status.UNKNOWN_OUTCOME, "external-id", "PROVIDER_TIMEOUT"));
    }

    @Test
    void resultsCarryOnlyProviderNeutralStableReasons() {
        assertTrue(ProviderOutcome.alreadyConverged("opaque-provider-id").isConverged());
        assertFalse(ProviderOutcome.converged(null).requiresReconciliation());
        assertThrows(IllegalArgumentException.class,
                () -> ProviderOutcome.failure(ProviderOutcome.Status.RETRYABLE_FAILURE, "timeout: raw response"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderOutcome.failure(ProviderOutcome.Status.CONVERGED, "BAD"));
    }

    @Test
    void capabilitiesDefaultToNothingNotSimulatedMoneyMovement() {
        var caps = ProviderCapabilities.none();
        assertFalse(caps.usage());
        assertFalse(caps.suspension());
        assertFalse(caps.resume());
        assertFalse(caps.delayedCancellation());
        assertFalse(caps.nativeIdempotency());
        assertFalse(caps.subscriptionObservation());
    }

    @Test
    void operationIntentRequiresTenantRevisionAndImmutableDigest() {
        String digest = "a".repeat(64);
        var intent = new ProviderOperationIntent(UUID.randomUUID(), "synthetic-tenant",
                "synthetic-billing-id", 3, "ENSURE_SUBSCRIPTION", digest);
        assertEquals(3, intent.authoritativeRevision());
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderOperationIntent(UUID.randomUUID(), " ", "billing", 3, "ENSURE", digest));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderOperationIntent(UUID.randomUUID(), "tenant", "billing", 0, "ENSURE", digest));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderOperationIntent(UUID.randomUUID(), "tenant", "billing", 1, "ENSURE", "invalid"));
    }
}
