package com.baobabplatform.subscriptions.store;

/** The stored outcome of one idempotent mutation, replayed for a repeated Idempotency-Key. */
public record IdempotencyRecord(String tenantId, String operation, String key, String requestHash, int status, String responseBody) {
}
