package com.baobabplatform.subscriptions.store;

import java.time.Instant;
import java.util.UUID;

/** A canonical event envelope recorded in the same transaction as the change it describes. */
public record OutboxEvent(UUID eventId, String eventType, String tenantId, String aggregateId, String envelopeJson, Instant occurredAt) {
}
