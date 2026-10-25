package com.baobabplatform.subscriptions.provider;

import java.util.Objects;

/**
 * Vendor-independent result of a provider operation (ADR-SUB-0015 sections
 * 12-13, 27, 36). Never infer that a timeout means a failed transaction.
 *
 * <p>UNKNOWN_OUTCOME is not retryable by default. A future reconciler must
 * observe the resource using durable operation and mapping evidence before
 * permitting another potentially monetary provider mutation.
 *
 * @param status provider-neutral outcome
 * @param providerReference opaque provider identity, only for converged results;
 *                          nullable for successful operations without an identity
 * @param reasonCode stable Baobab code for a non-converged result, not provider
 *                   response text, credentials or personally identifiable data
 */
public record ProviderOutcome(Status status, String providerReference, String reasonCode) {
    public enum Status {
        CONVERGED,
        ALREADY_CONVERGED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        CONFLICT,
        UNKNOWN_OUTCOME
    }

    public ProviderOutcome {
        Objects.requireNonNull(status, "status");
        boolean converged = status == Status.CONVERGED || status == Status.ALREADY_CONVERGED;
        if (converged && reasonCode != null) {
            throw new IllegalArgumentException("a converged operation has no failure reason");
        }
        if (!converged && providerReference != null) {
            throw new IllegalArgumentException("a non-converged result cannot assert a provider reference");
        }
        if (!converged && (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}"))) {
            throw new IllegalArgumentException("a failed or ambiguous operation needs a stable reason code");
        }
        if (providerReference != null && providerReference.isBlank()) {
            throw new IllegalArgumentException("a provider reference cannot be blank");
        }
    }

    public static ProviderOutcome converged(String providerReference) {
        return new ProviderOutcome(Status.CONVERGED, providerReference, null);
    }

    public static ProviderOutcome alreadyConverged(String providerReference) {
        return new ProviderOutcome(Status.ALREADY_CONVERGED, providerReference, null);
    }

    public static ProviderOutcome failure(Status status, String reasonCode) {
        if (status == Status.CONVERGED || status == Status.ALREADY_CONVERGED) {
            throw new IllegalArgumentException("use converged factories for successful outcomes");
        }
        return new ProviderOutcome(status, null, reasonCode);
    }

    public boolean isConverged() {
        return status == Status.CONVERGED || status == Status.ALREADY_CONVERGED;
    }

    public boolean requiresReconciliation() {
        return status == Status.UNKNOWN_OUTCOME;
    }
}
