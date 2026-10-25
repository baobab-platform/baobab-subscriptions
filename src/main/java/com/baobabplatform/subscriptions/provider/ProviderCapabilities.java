package com.baobabplatform.subscriptions.provider;

/**
 * Capabilities a provider can truthfully perform, rather than inferred support
 * from its implementation name (ADR-SUB-0015 sections 51-52).
 *
 * <p>Capability negotiation must occur before monetary operations. This value
 * describes adapter functionality, not provider health or financial readiness.
 */
public record ProviderCapabilities(
        boolean usage,
        boolean suspension,
        boolean resume,
        boolean delayedCancellation,
        boolean catalogVersioning,
        boolean nativeIdempotency,
        boolean accountObservation,
        boolean subscriptionObservation) {

    /** A deliberately non-monetary provider has no externally supported features. */
    public static ProviderCapabilities none() {
        return new ProviderCapabilities(false, false, false, false, false, false, false, false);
    }
}
