package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single-process store for development, integration and tests. Units of work
 * are serialised and their writes are staged, so a failed unit changes nothing.
 * Configuration refuses it in staging and production.
 */
public final class InMemoryBillingStore implements BillingStore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    private final Map<String, Projection> projections = new HashMap<>();
    private final Map<String, String> byProductSubscription = new HashMap<>();
    private final Map<String, UsageRecord> usageBySource = new LinkedHashMap<>();
    private final List<OutboxEvent> events = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final Map<UUID, ProviderOperation> providerOperations = new HashMap<>();
    private final Map<String, UUID> providerOperationKeys = new HashMap<>();
    private final Map<String, ProviderMapping> providerMappings = new HashMap<>();
    private final Map<String, String> providerNativeIds = new HashMap<>();

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }

    @Override
    public <T> T transact(Work<T> work) {
        lock.lock();
        try {
            Staged tx = new Staged();
            T result = work.run(tx);
            tx.commit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public List<OutboxEvent> events(String tenantId) {
        lock.lock();
        try {
            return events.stream().filter(e -> e.tenantId().equals(tenantId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<AuditRecord> audit(String tenantId) {
        lock.lock();
        try {
            return audit.stream().filter(a -> a.tenantId().equals(tenantId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    private final class Staged implements Tx {
        private final Map<String, IdempotencyRecord> newIdempotency = new HashMap<>();
        private final Map<String, Projection> newProjections = new HashMap<>();
        private final Map<String, String> newIndex = new HashMap<>();
        private final Map<String, UsageRecord> newUsage = new LinkedHashMap<>();
        private final List<OutboxEvent> newEvents = new ArrayList<>();
        private final List<AuditRecord> newAudit = new ArrayList<>();
        private final Map<UUID, ProviderOperation> newOperations = new HashMap<>();
        private final Map<String, UUID> newOperationKeys = new HashMap<>();
        private final Map<String, ProviderMapping> newMappings = new HashMap<>();
        private final Map<String, String> newNativeIds = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> idempotency(String tenantId, String operation, String key) {
            String k = key(tenantId, operation, key);
            return Optional.ofNullable(newIdempotency.getOrDefault(k, idempotency.get(k)));
        }

        @Override
        public void saveIdempotency(IdempotencyRecord record) {
            String k = key(record.tenantId(), record.operation(), record.key());
            if (idempotency.containsKey(k) || newIdempotency.containsKey(k)) {
                throw new ConflictException("idempotency key already recorded", null);
            }
            newIdempotency.put(k, record);
        }

        @Override
        public Optional<Projection> projectionForProductSubscription(String tenantId, String productSubscriptionId) {
            String k = key(tenantId, productSubscriptionId);
            String id = newIndex.getOrDefault(k, byProductSubscription.get(k));
            return id == null ? Optional.empty() : projection(tenantId, id);
        }

        @Override
        public Optional<Projection> projection(String tenantId, String billingSubscriptionId) {
            String k = key(tenantId, billingSubscriptionId);
            return Optional.ofNullable(newProjections.getOrDefault(k, projections.get(k)));
        }

        @Override
        public void insertProjection(Projection p) {
            String k = key(p.tenantId(), p.billingSubscriptionId());
            String index = key(p.tenantId(), p.productSubscriptionId());
            if (projections.containsKey(k) || newProjections.containsKey(k)
                    || byProductSubscription.containsKey(index) || newIndex.containsKey(index)) {
                throw new ConflictException("the product subscription already has a projection", null);
            }
            newProjections.put(k, p);
            newIndex.put(index, p.billingSubscriptionId());
        }

        @Override
        public void updateProjection(Projection p, long expectedVersion) {
            String k = key(p.tenantId(), p.billingSubscriptionId());
            Projection current = newProjections.getOrDefault(k, projections.get(k));
            if (current == null || current.version() != expectedVersion) {
                throw new ConflictException("the projection changed concurrently", null);
            }
            newProjections.put(k, p);
        }

        @Override
        public Optional<UsageRecord> usageBySource(String tenantId, String billingSubscriptionId, String metricKey, String sourceReference) {
            String k = key(tenantId, billingSubscriptionId, metricKey, sourceReference);
            return Optional.ofNullable(newUsage.getOrDefault(k, usageBySource.get(k)));
        }

        @Override
        public void insertUsage(UsageRecord u) {
            String k = key(u.tenantId(), u.billingSubscriptionId(), u.metricKey(), u.sourceReference());
            if (usageBySource.containsKey(k) || newUsage.containsKey(k)) {
                throw new ConflictException("usage already recorded for this source", null);
            }
            newUsage.put(k, u);
        }

        @Override
        public Optional<ProviderOperation> providerOperation(String tenantId, UUID operationId) {
            ProviderOperation operation = newOperations.getOrDefault(operationId, providerOperations.get(operationId));
            return operation != null && operation.tenantId().equals(tenantId)
                    ? Optional.of(operation) : Optional.empty();
        }

        @Override
        public Optional<ProviderOperation> providerOperationForKey(
                String tenantId, String providerKind, String operationKind, String keyValue) {
            String k = key(tenantId, providerKind, operationKind, keyValue);
            UUID id = newOperationKeys.getOrDefault(k, providerOperationKeys.get(k));
            return id == null ? Optional.empty() : providerOperation(tenantId, id);
        }

        @Override
        public void insertProviderOperation(ProviderOperation operation) {
            if (projection(operation.tenantId(), operation.billingSubscriptionId()).isEmpty()) {
                throw new IllegalArgumentException("no tenant-owned projection for provider operation");
            }
            String k = key(operation.tenantId(), operation.providerKind(),
                    operation.operationKind(), operation.idempotencyKey());
            if (providerOperations.containsKey(operation.operationId()) || newOperations.containsKey(operation.operationId())
                    || providerOperationKeys.containsKey(k) || newOperationKeys.containsKey(k)) {
                throw new ConflictException("duplicate provider operation", null);
            }
            newOperations.put(operation.operationId(), operation);
            newOperationKeys.put(k, operation.operationId());
        }

        @Override
        public void updateProviderOperation(ProviderOperation next, ProviderOperation.State oldState, long oldEpoch) {
            ProviderOperation prior = providerOperation(next.tenantId(), next.operationId())
                    .orElseThrow(() -> new ConflictException("provider operation missing", null));
            if (prior.state() != oldState || prior.leaseEpoch() != oldEpoch
                    || !prior.providerKind().equals(next.providerKind())
                    || !prior.billingSubscriptionId().equals(next.billingSubscriptionId())
                    || !prior.idempotencyKey().equals(next.idempotencyKey())
                    || !prior.requestSha256().equals(next.requestSha256())) {
                throw new ConflictException("stale provider operation lease", null);
            }
            newOperations.put(next.operationId(), next);
        }

        @Override
        public Optional<ProviderMapping> providerMapping(
                String tenantId, String providerKind, String billingSubscriptionId) {
            String k = key(tenantId, providerKind, billingSubscriptionId);
            return Optional.ofNullable(newMappings.getOrDefault(k, providerMappings.get(k)));
        }

        @Override
        public void insertProviderMapping(ProviderMapping mapping) {
            if (projection(mapping.tenantId(), mapping.billingSubscriptionId()).isEmpty()) {
                throw new IllegalArgumentException("provider mapping requires a tenant-owned projection");
            }
            String k = key(mapping.tenantId(), mapping.providerKind(), mapping.billingSubscriptionId());
            String nativeKey = key(mapping.tenantId(), mapping.providerKind(), mapping.providerResourceId());
            if (providerMappings.containsKey(k) || newMappings.containsKey(k)
                    || providerNativeIds.containsKey(nativeKey) || newNativeIds.containsKey(nativeKey)) {
                throw new ConflictException("duplicate provider mapping", null);
            }
            newMappings.put(k, mapping);
            newNativeIds.put(nativeKey, mapping.billingSubscriptionId());
        }

        @Override
        public void appendEvent(OutboxEvent event) {
            newEvents.add(event);
        }

        @Override
        public void appendAudit(AuditRecord record) {
            newAudit.add(record);
        }

        void commit() {
            idempotency.putAll(newIdempotency);
            projections.putAll(newProjections);
            byProductSubscription.putAll(newIndex);
            usageBySource.putAll(newUsage);
            events.addAll(newEvents);
            audit.addAll(newAudit);
            providerOperations.putAll(newOperations);
            providerOperationKeys.putAll(newOperationKeys);
            providerMappings.putAll(newMappings);
            providerNativeIds.putAll(newNativeIds);
        }
    }
}
