package com.baobabplatform.subscriptions.payments;

/**
 * The boundary to baobab-payments (ADR-PAY-0001). Only billing whose policy
 * says payment execution is REQUIRED may consult it; INTERNAL and other
 * zero-charge billing never touches it, not even to ask whether it is
 * configured.
 */
public interface PaymentsPort {
    /** Whether a real payment path exists for billing to use. */
    boolean configured();

    /** No payment path: the client to baobab-payments is not built yet. */
    static PaymentsPort notConfigured() {
        return () -> false;
    }
}
