package com.baobabplatform.subscriptions.domain;

/**
 * Readiness as separate facts, not one boolean (ADR-SUB-0003 section 46,
 * ADR-SUB-0006 section 57). For zero-charge policies billing configuration,
 * provider and payment path are not required and report true.
 */
public record ReadinessFacts(
        boolean classificationValid,
        boolean projectionValid,
        boolean meteringAvailable,
        boolean billingConfigurationComplete,
        boolean providerReady,
        boolean paymentPathReady) {
}
