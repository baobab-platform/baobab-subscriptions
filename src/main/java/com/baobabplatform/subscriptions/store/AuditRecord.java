package com.baobabplatform.subscriptions.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A material audit record (ADR-SUB-0016 sections 35-36), written in the same
 * transaction as the change it evidences and never updated. It is distinct
 * from application logging and from events.
 */
public record AuditRecord(
        UUID auditId,
        Instant occurredAt,
        String principalType,
        String workloadId,
        String actorId,
        String tenantId,
        String platformAccountId,
        String resourceType,
        String resourceId,
        String operation,
        String previousState,
        String resultingState,
        String reason,
        String idempotencyKey,
        String correlationId,
        Long authoritativeRevision,
        int policyVersion,
        String providerReference,
        String outcome) {
}
