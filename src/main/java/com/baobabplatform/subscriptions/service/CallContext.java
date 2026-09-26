package com.baobabplatform.subscriptions.service;

/**
 * Who is asking and under which request identity: the authenticated workload
 * (its client and subject), the Idempotency-Key of a mutation, and the
 * correlation identifier. Carried into audit records and events.
 */
public record CallContext(String workloadId, String actorId, String idempotencyKey, String correlationId) {
    /** The same call with the mutation's Idempotency-Key. */
    public CallContext withKey(String key) {
        return new CallContext(workloadId, actorId, key, correlationId);
    }
}
