package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

/**
 * This engine's own persistence (ADR-SUB-0001 section 8): it never reads the
 * Control Plane's database. Every read and write is scoped by tenant_id, so
 * another tenant's identifiers are simply not found.
 */
public interface BillingStore extends AutoCloseable {
    /** Runs work atomically. A {@link ConflictException} rolls back and the caller may retry. */
    <T> T transact(Work<T> work);

    /** Whether the store can serve requests. */
    boolean ping();

    /** Events recorded for a tenant, oldest first (for diagnostics and tests; no relay exists yet). */
    List<OutboxEvent> events(String tenantId);

    /** Audit records for a tenant, oldest first. */
    List<AuditRecord> audit(String tenantId);

    @Override
    void close();

    /** A unit of work. */
    @FunctionalInterface
    interface Work<T> {
        T run(Tx tx);
    }

    /** The operations available inside a unit of work. */
    interface Tx {
        Optional<IdempotencyRecord> idempotency(String tenantId, String operation, String key);

        void saveIdempotency(IdempotencyRecord record);

        /** The projection of a ProductSubscription, locked for update. */
        Optional<Projection> projectionForProductSubscription(String tenantId, String productSubscriptionId);

        /** The projection, locked for update. */
        Optional<Projection> projection(String tenantId, String billingSubscriptionId);

        void insertProjection(Projection projection);

        /** Replaces the projection; fails with ConflictException unless it is at expectedVersion. */
        void updateProjection(Projection projection, long expectedVersion);

        Optional<UsageRecord> usageBySource(String tenantId, String billingSubscriptionId, String metricKey, String sourceReference);

        void insertUsage(UsageRecord usage);

        /** Tenant-scoped durable provider journal (never Control Plane authority). */
        Optional<ProviderOperation> providerOperation(String tenantId, UUID operationId);

        Optional<ProviderOperation> providerOperationForKey(
                String tenantId, String providerKind, String operationKind, String key);

        /**
         * Unresolved operations against the same tenant/provider/subscription,
         * including older CP revisions. Never scope only by operation key.
         */
        List<ProviderOperation> unresolvedProviderOperations(
                String tenantId, String providerKind, String billingSubscriptionId);

        void insertProviderOperation(ProviderOperation operation);

        /**
         * Compare-and-swap state AND lease epoch, preventing an old worker from
         * acknowledging or reclassifying another worker's leased operation.
         */
        void updateProviderOperation(ProviderOperation next, ProviderOperation.State oldState, long oldEpoch);

        Optional<ProviderMapping> providerMapping(String tenantId, String providerKind, String billingSubscriptionId);

        /** Durable uniqueness; a second provider ID for the same resource is a conflict. */
        void insertProviderMapping(ProviderMapping mapping);

        void appendEvent(OutboxEvent event);

        void appendAudit(AuditRecord record);
    }
}
