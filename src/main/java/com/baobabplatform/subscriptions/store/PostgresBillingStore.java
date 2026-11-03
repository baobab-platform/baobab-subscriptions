package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL persistence through a HikariCP pool. Schema changes are versioned SQL applied at startup. */
public final class PostgresBillingStore implements BillingStore, FoundingEventInbox, CpSubscriptionLifecycleInbox {
    private static final String[] MIGRATIONS = {"db/V1__billing.sql", "db/V2__audit_and_lifecycle.sql",
            "db/V3__founding_lifecycle_inbox.sql", "db/V4__founding_inbox_verified_workload.sql",
            "db/V5__provider_operations_and_mappings.sql", "db/V6__provider_operation_outcomes.sql",
            "db/V7__provider_supersession.sql",
            "db/V8__cp_subscription_lifecycle_inbox.sql",
            "db/V9__provider_reconciliation_retry.sql", "db/V10__outbox_delivery_custody.sql",
            "db/V11__outbox_replay_and_retry_horizon.sql"};
    /** Serialises migrations across replicas starting together. */
    private static final long MIGRATION_LOCK = 0x62616f6261627375L;

    private final HikariDataSource pool;

    public PostgresBillingStore(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (user != null) {
            config.setUsername(user);
        }
        if (password != null) {
            config.setPassword(password);
        }
        config.setPoolName("baobab-subscriptions");
        config.setMaximumPoolSize(10);
        config.setAutoCommit(false);
        config.setConnectionTimeout(5_000);
        this.pool = new HikariDataSource(config);
        migrate();
    }

    @Override
    public FoundingEventInbox.Receipt receive(byte[] rawEvent, String authenticatedClientId) {
        if (authenticatedClientId == null || !authenticatedClientId.matches("[A-Za-z0-9._:-]{3,200}")) {
            throw new IllegalArgumentException("invalid authenticated workload client ID");
        }
        if (rawEvent == null || rawEvent.length == 0 || rawEvent.length > 64 * 1024) {
            throw new IllegalArgumentException("invalid founding event size");
        }
        JsonNode event = FoundingEventInbox.parse(rawEvent);
        UUID eventId = FoundingEventInbox.verify(event);
        String dataType = event.path("type").asText();
        String source = event.path("source").asText();
        String dataId = dataType.contains("founding-sponsorship.")
                ? event.path("data").path("sponsorship_id").asText()
                : event.path("data").path("deferral_id").asText();
        final String sha256;
        try {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawEvent));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (Connection c = pool.getConnection()) {
            int inserted;
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO billing.founding_event_inbox "
                    + "(event_id,event_type,event_source,received_by_client_id,aggregate_id,payload_sha256,envelope) "
                    + "VALUES(?,?,?,?,?::uuid,?,?::jsonb) ON CONFLICT (event_id) DO NOTHING")) {
                q.setObject(1, eventId);
                q.setString(2, dataType);
                q.setString(3, source);
                q.setString(4, authenticatedClientId);
                q.setString(5, dataId);
                q.setString(6, sha256);
                q.setString(7, new String(rawEvent, StandardCharsets.UTF_8));
                inserted = q.executeUpdate();
            }
            if (inserted == 0) {
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT payload_sha256,received_by_client_id FROM billing.founding_event_inbox WHERE event_id = ?")) {
                    q.setObject(1, eventId);
                    try (ResultSet rs = q.executeQuery()) {
                        if (!rs.next() || !sha256.equals(rs.getString(1)) || !authenticatedClientId.equals(rs.getString(2))) {
                            throw new IllegalArgumentException("event ID replayed with changed payload");
                        }
                    }
                }
            }
            c.commit();
            return new FoundingEventInbox.Receipt(eventId, inserted == 0);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /**
     * CP subscription events are notifications only. Receipt transaction
     * commits a stable (source,id,hash,verified workload) before HTTP 202.
     * A duplicate event with altered bytes or verified principal is refused.
     */
    @Override
    public CpSubscriptionLifecycleInbox.Receipt receiveSubscriptionEvent(byte[] raw, String authenticatedClientId) {
        if (authenticatedClientId == null
                || !authenticatedClientId.matches("[A-Za-z0-9._:-]{3,200}")) {
            throw new IllegalArgumentException("invalid authenticated CP workload identity");
        }
        CpSubscriptionLifecycleInbox.Notification notice = CpSubscriptionLifecycleInbox.verify(raw);
        final String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        try (Connection c = pool.getConnection()) {
            int inserted;
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO billing.cp_subscription_lifecycle_inbox "
                    + "(event_id,event_source,event_type,tenant_id,subscription_id,received_by_client_id,"
                    + "payload_sha256,envelope) VALUES (?,?,?,?,?,?,?,?::jsonb) "
                    + "ON CONFLICT (event_source,event_id) DO NOTHING")) {
                q.setObject(1, notice.eventId());
                q.setString(2, notice.source());
                q.setString(3, notice.type());
                q.setString(4, notice.tenantId());
                q.setString(5, notice.subscriptionId());
                q.setString(6, authenticatedClientId);
                q.setString(7, hash);
                q.setString(8, new String(raw, StandardCharsets.UTF_8));
                inserted = q.executeUpdate();
            }
            if (inserted == 0) {
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT payload_sha256,received_by_client_id,tenant_id,subscription_id "
                        + "FROM billing.cp_subscription_lifecycle_inbox "
                        + "WHERE event_source = ? AND event_id = ?")) {
                    q.setString(1, notice.source());
                    q.setObject(2, notice.eventId());
                    try (ResultSet rs = q.executeQuery()) {
                        if (!rs.next() || !hash.equals(rs.getString(1))
                                || !authenticatedClientId.equals(rs.getString(2))
                                || !notice.tenantId().equals(rs.getString(3))
                                || !notice.subscriptionId().equals(rs.getString(4))) {
                            throw new ConflictException("CP event ID reused with different custody", null);
                        }
                    }
                }
            }
            c.commit();
            return new CpSubscriptionLifecycleInbox.Receipt(
                    notice.eventId(), inserted == 0, "PENDING_CP_REFRESH");
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    @Override
    public List<CpSubscriptionLifecycleInbox.Notification> pending(String tenantId, int limit) {
        if (tenantId == null || tenantId.isBlank() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("tenant and bounded limit required");
        }
        try (Connection c = pool.getConnection();
             PreparedStatement q = c.prepareStatement(
                     "SELECT event_id,event_type,subscription_id,event_source "
                     + "FROM billing.cp_subscription_lifecycle_inbox "
                     + "WHERE tenant_id = ? AND status = 'PENDING_CP_REFRESH' "
                     + "ORDER BY received_at,event_id LIMIT ?")) {
            q.setString(1, tenantId);
            q.setInt(2, limit);
            List<CpSubscriptionLifecycleInbox.Notification> result = new ArrayList<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    result.add(new CpSubscriptionLifecycleInbox.Notification(
                            rs.getObject(1, UUID.class), rs.getString(2),
                            tenantId, rs.getString(3), rs.getString(4)));
                }
            }
            c.rollback();
            return result;
        } catch (SQLException e) {
            throw translate(e);
        }
    }


    /**
     * Bounded tenant-specific work scan, never a cross-tenant provider
     * operation listing. Rows with uncertain outcomes are observation-only.
     * Advisory work leases still reside in ProviderReconciliationService.
     */
    public List<ProviderOperation> dueProviderObservations(String tenantId, int limit, java.time.Instant now) {
        if (tenantId == null || tenantId.isBlank() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("tenant and bounded limit required");
        }
        try (Connection c = pool.getConnection();
             PreparedStatement q = c.prepareStatement(
                     "SELECT p.* FROM billing.provider_operation p "
                     + "LEFT JOIN billing.provider_reconciliation_retry r "
                     + "ON p.tenant_id=r.tenant_id AND p.operation_id=r.operation_id "
                     + "WHERE p.tenant_id=? AND p.provider_kind='KILL_BILL' "
                     + "AND ((p.state='SENT' AND p.lease_expires_at<=?) "
                     + "OR (p.state='UNKNOWN_OUTCOME' AND (p.lease_expires_at IS NULL "
                     + "OR p.lease_expires_at<=?))) "
                     + "AND (r.status IS NULL OR (r.status='SCHEDULED' AND r.next_attempt_at<=?)) "
                     + "ORDER BY p.updated_at,p.operation_id LIMIT ?")) {
            q.setString(1, tenantId);
            q.setTimestamp(2, Timestamp.from(now));
            q.setTimestamp(3, Timestamp.from(now));
            q.setTimestamp(4, Timestamp.from(now));
            q.setInt(5, limit);
            List<ProviderOperation> due = new ArrayList<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) due.add(SqlTx.readProviderOperation(rs));
            }
            c.rollback();
            return due;
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    /** Tenant and state bound discovery; all claims remain per-operation CAS. */
    public List<ProviderOperation> preparedProviderOperations(String tenantId, int limit) {
        if (tenantId == null || tenantId.isBlank() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("tenant and bounded limit required");
        }
        try (Connection c = pool.getConnection();
             PreparedStatement q = c.prepareStatement(
                     "SELECT * FROM billing.provider_operation WHERE tenant_id=? "
                     + "AND provider_kind='KILL_BILL' AND state='PREPARED' "
                     + "ORDER BY created_at,operation_id LIMIT ?")) {
            q.setString(1, tenantId);
            q.setInt(2, limit);
            List<ProviderOperation> result = new ArrayList<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) result.add(SqlTx.readProviderOperation(rs));
            }
            c.rollback();
            return result;
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    /** Durable cooldown and exhaustion. No provider request or CP mutation here. */
    public void recordReconciliationAttempt(String tenantId, UUID operationId,
                                             boolean resolved, String reason,
                                             java.time.Instant now) {
        if (tenantId == null || tenantId.isBlank()
                || reason != null && !reason.matches("[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("invalid retry custody");
        }
        try (Connection c = pool.getConnection()) {
            // The operation must already exist and belong to the tenant.
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO billing.provider_reconciliation_retry "
                    + "(tenant_id,operation_id,attempts,next_attempt_at,status,last_reason_code) "
                    + "VALUES (?,?,0,?,'SCHEDULED',NULL) ON CONFLICT DO NOTHING")) {
                q.setString(1, tenantId);
                q.setObject(2, operationId);
                q.setTimestamp(3, Timestamp.from(now));
                q.executeUpdate();
            }
            int attempts;
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT attempts FROM billing.provider_reconciliation_retry "
                    + "WHERE tenant_id=? AND operation_id=? FOR UPDATE")) {
                q.setString(1, tenantId);
                q.setObject(2, operationId);
                try (ResultSet rs = q.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("provider retry custody missing");
                    attempts = Math.min(8, rs.getInt(1) + 1);
                }
            }
            // Deterministic bounded jitter (0..9s) so retry cadence remains
            // stable and inspectable across restarts.
            long seconds = Math.min(3600, 10L << Math.min(attempts - 1, 8))
                    + Math.floorMod(operationId.hashCode(), 10);
            try (PreparedStatement q = c.prepareStatement(
                    "UPDATE billing.provider_reconciliation_retry SET attempts=?, "
                    + "next_attempt_at=?,status=?,last_reason_code=?,updated_at=? "
                    + "WHERE tenant_id=? AND operation_id=?")) {
                q.setInt(1, attempts);
                q.setTimestamp(2, Timestamp.from(now.plusSeconds(seconds)));
                q.setString(3, resolved ? "RESOLVED" : attempts >= 8 ? "EXHAUSTED" : "SCHEDULED");
                q.setString(4, reason);
                q.setTimestamp(5, Timestamp.from(now));
                q.setString(6, tenantId);
                q.setObject(7, operationId);
                q.executeUpdate();
            }
            c.commit();
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    /** Operational aggregate: no native IDs, person data, or provider secrets. */
    public java.util.Map<String, Long> reconciliationCounts(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant required");
        try (Connection c = pool.getConnection();
             PreparedStatement q = c.prepareStatement(
                     "SELECT p.state,COUNT(*) FROM billing.provider_operation p "
                     + "WHERE p.tenant_id=? AND p.provider_kind='KILL_BILL' GROUP BY p.state")) {
            q.setString(1, tenantId);
            java.util.Map<String, Long> counts = new java.util.HashMap<>();
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            }
            try (PreparedStatement e = c.prepareStatement(
                    "SELECT COUNT(*) FROM billing.provider_reconciliation_retry "
                    + "WHERE tenant_id=? AND status='EXHAUSTED'")) {
                e.setString(1, tenantId);
                try (ResultSet rs = e.executeQuery()) {
                    if (rs.next()) counts.put("EXHAUSTED", rs.getLong(1));
                }
            }
            c.rollback();
            return java.util.Map.copyOf(counts);
        } catch (SQLException error) {
            throw translate(error);
        }
    }

    private void migrate() {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT pg_advisory_xact_lock(" + MIGRATION_LOCK + ")");
            s.execute("CREATE TABLE IF NOT EXISTS public.baobab_subscriptions_migrations (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
            for (int i = 0; i < MIGRATIONS.length; i++) {
                int version = i + 1;
                try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM public.baobab_subscriptions_migrations WHERE version = ?")) {
                    q.setInt(1, version);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) {
                            continue;
                        }
                    }
                }
                s.execute(resource(MIGRATIONS[i]));
                try (PreparedStatement q = c.prepareStatement("INSERT INTO public.baobab_subscriptions_migrations (version) VALUES (?)")) {
                    q.setInt(1, version);
                    q.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("database migration failed", e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = PostgresBillingStore.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public <T> T transact(Work<T> work) {
        try (Connection c = pool.getConnection()) {
            try {
                T result = work.run(new SqlTx(c));
                c.commit();
                return result;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } catch (SQLException e) {
                c.rollback();
                throw translate(e);
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("23505".equals(state) || "40001".equals(state) || "40P01".equals(state)) {
            return new ConflictException("a concurrent writer won", e);
        }
        return new IllegalStateException("database error " + state, e);
    }

    @Override
    public boolean ping() {
        try (Connection c = pool.getConnection()) {
            boolean valid = c.isValid(2);
            c.rollback();
            return valid;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Internal publisher custody; never used by HTTP clients for arbitrary event publication. */
    public OutboxDeliveryQueue outboxDeliveryQueue() {
        return new PostgresOutboxDeliveryQueue(pool);
    }

    @Override
    public List<OutboxEvent> events(String tenantId) {
        return transact(tx -> ((SqlTx) tx).events(tenantId));
    }

    @Override
    public List<AuditRecord> audit(String tenantId) {
        return transact(tx -> ((SqlTx) tx).audit(tenantId));
    }

    @Override
    public void close() {
        pool.close();
    }

    private static String json(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return Json.mapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored " + type.getSimpleName() + " is unreadable", e);
        }
    }

    private static final class SqlTx implements Tx {
        private final Connection c;

        SqlTx(Connection c) {
            this.c = c;
        }

        private RuntimeException fail(SQLException e) {
            return translate(e);
        }

        @Override
        public Optional<IdempotencyRecord> idempotency(String tenantId, String operation, String key) {
            try (PreparedStatement q = c.prepareStatement("SELECT request_hash, status, response_body FROM billing.idempotency "
                    + "WHERE tenant_id = ? AND operation = ? AND idempotency_key = ?")) {
                q.setString(1, tenantId);
                q.setString(2, operation);
                q.setString(3, key);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new IdempotencyRecord(tenantId, operation, key, rs.getString(1), rs.getInt(2), rs.getString(3)))
                            : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void saveIdempotency(IdempotencyRecord r) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.idempotency (tenant_id, operation, idempotency_key, "
                    + "request_hash, status, response_body) VALUES (?, ?, ?, ?, ?, ?)")) {
                q.setString(1, r.tenantId());
                q.setString(2, r.operation());
                q.setString(3, r.key());
                q.setString(4, r.requestHash());
                q.setInt(5, r.status());
                q.setString(6, r.responseBody());
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        private Optional<Projection> oneProjection(String where, String tenantId, String id) {
            try (PreparedStatement q = c.prepareStatement("SELECT document::text FROM billing.projection WHERE tenant_id = ? AND "
                    + where + " = ? FOR UPDATE")) {
                q.setString(1, tenantId);
                q.setString(2, id);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs.getString(1), Projection.class)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public Optional<Projection> projectionForProductSubscription(String tenantId, String productSubscriptionId) {
            return oneProjection("product_subscription_id", tenantId, productSubscriptionId);
        }

        @Override
        public Optional<Projection> projection(String tenantId, String billingSubscriptionId) {
            return oneProjection("billing_subscription_id", tenantId, billingSubscriptionId);
        }

        @Override
        public void insertProjection(Projection p) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.projection (billing_subscription_id, tenant_id, "
                    + "product_subscription_id, version, document, updated_at, authoritative_revision) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")) {
                q.setString(1, p.billingSubscriptionId());
                q.setString(2, p.tenantId());
                q.setString(3, p.productSubscriptionId());
                q.setLong(4, p.version());
                q.setString(5, json(p));
                q.setTimestamp(6, Timestamp.from(p.updatedAt()));
                q.setLong(7, p.authoritativeRevision());
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void updateProjection(Projection p, long expectedVersion) {
            try (PreparedStatement q = c.prepareStatement("UPDATE billing.projection SET version = ?, document = ?::jsonb, "
                    + "updated_at = ?, authoritative_revision = ? WHERE tenant_id = ? AND billing_subscription_id = ? AND version = ?")) {
                q.setLong(1, p.version());
                q.setString(2, json(p));
                q.setTimestamp(3, Timestamp.from(p.updatedAt()));
                q.setLong(4, p.authoritativeRevision());
                q.setString(5, p.tenantId());
                q.setString(6, p.billingSubscriptionId());
                q.setLong(7, expectedVersion);
                if (q.executeUpdate() != 1) {
                    throw new ConflictException("the projection changed concurrently", null);
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public Optional<UsageRecord> usageBySource(String tenantId, String billingSubscriptionId, String metricKey, String sourceReference) {
            try (PreparedStatement q = c.prepareStatement("SELECT document::text FROM billing.usage_record WHERE tenant_id = ? "
                    + "AND billing_subscription_id = ? AND metric_key = ? AND source_reference = ?")) {
                q.setString(1, tenantId);
                q.setString(2, billingSubscriptionId);
                q.setString(3, metricKey);
                q.setString(4, sourceReference);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs.getString(1), UsageRecord.class)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void insertUsage(UsageRecord u) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.usage_record (usage_record_id, tenant_id, "
                    + "billing_subscription_id, metric_key, source_reference, document, recorded_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")) {
                q.setString(1, u.usageRecordId());
                q.setString(2, u.tenantId());
                q.setString(3, u.billingSubscriptionId());
                q.setString(4, u.metricKey());
                q.setString(5, u.sourceReference());
                q.setString(6, json(u));
                q.setTimestamp(7, Timestamp.from(u.recordedAt()));
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }


        private static ProviderOperation readProviderOperation(ResultSet rs) throws SQLException {
            return new ProviderOperation((UUID) rs.getObject("operation_id"),
                    rs.getString("tenant_id"), rs.getString("billing_subscription_id"),
                    rs.getString("provider_kind"), rs.getString("operation_kind"),
                    rs.getString("idempotency_key"), rs.getString("request_sha256"),
                    rs.getLong("authoritative_revision"),
                    ProviderOperation.State.valueOf(rs.getString("state")),
                    rs.getString("lease_owner"), rs.getLong("lease_epoch"),
                    rs.getTimestamp("lease_expires_at") == null ? null
                            : rs.getTimestamp("lease_expires_at").toInstant(),
                    rs.getString("reason_code"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        @Override
        public Optional<ProviderOperation> providerOperation(String tenantId, UUID operationId) {
            try (PreparedStatement q = c.prepareStatement("SELECT * FROM billing.provider_operation "
                    + "WHERE tenant_id = ? AND operation_id = ?")) {
                q.setString(1, tenantId);
                q.setObject(2, operationId);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? Optional.of(readProviderOperation(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public Optional<ProviderOperation> providerOperationForKey(
                String tenantId, String providerKind, String operationKind, String key) {
            try (PreparedStatement q = c.prepareStatement("SELECT * FROM billing.provider_operation "
                    + "WHERE tenant_id = ? AND provider_kind = ? AND operation_kind = ? "
                    + "AND idempotency_key = ?")) {
                q.setString(1, tenantId);
                q.setString(2, providerKind);
                q.setString(3, operationKind);
                q.setString(4, key);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? Optional.of(readProviderOperation(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public List<ProviderOperation> unresolvedProviderOperations(
                String tenantId, String providerKind, String billingSubscriptionId) {
            try (PreparedStatement q = c.prepareStatement("SELECT * FROM billing.provider_operation "
                    + "WHERE tenant_id = ? AND provider_kind = ? AND billing_subscription_id = ? "
                    + "AND state NOT IN ('CONVERGED', 'ALREADY_CONVERGED', 'RECONCILED', 'SUPERSEDED') "
                    + "ORDER BY created_at, operation_id")) {
                q.setString(1, tenantId);
                q.setString(2, providerKind);
                q.setString(3, billingSubscriptionId);
                try (ResultSet rs = q.executeQuery()) {
                    List<ProviderOperation> unresolved = new ArrayList<>();
                    while (rs.next()) {
                        unresolved.add(readProviderOperation(rs));
                    }
                    return unresolved;
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void insertProviderOperation(ProviderOperation op) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.provider_operation "
                    + "(operation_id, tenant_id, billing_subscription_id, provider_kind, operation_kind, "
                    + "idempotency_key, request_sha256, authoritative_revision, state, lease_owner, "
                    + "lease_epoch, lease_expires_at, reason_code, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                q.setObject(1, op.operationId());
                q.setString(2, op.tenantId());
                q.setString(3, op.billingSubscriptionId());
                q.setString(4, op.providerKind());
                q.setString(5, op.operationKind());
                q.setString(6, op.idempotencyKey());
                q.setString(7, op.requestSha256());
                q.setLong(8, op.authoritativeRevision());
                q.setString(9, op.state().name());
                q.setString(10, op.leaseOwner());
                q.setLong(11, op.leaseEpoch());
                q.setTimestamp(12, op.leaseExpiresAt() == null ? null : Timestamp.from(op.leaseExpiresAt()));
                q.setString(13, op.reasonCode());
                q.setTimestamp(14, Timestamp.from(op.createdAt()));
                q.setTimestamp(15, Timestamp.from(op.updatedAt()));
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void updateProviderOperation(ProviderOperation op, ProviderOperation.State oldState, long oldEpoch) {
            try (PreparedStatement q = c.prepareStatement("UPDATE billing.provider_operation SET "
                    + "state = ?, lease_owner = ?, lease_epoch = ?, lease_expires_at = ?, "
                    + "reason_code = ?, updated_at = ? WHERE tenant_id = ? AND operation_id = ? "
                    + "AND state = ? AND lease_epoch = ?")) {
                q.setString(1, op.state().name());
                q.setString(2, op.leaseOwner());
                q.setLong(3, op.leaseEpoch());
                q.setTimestamp(4, op.leaseExpiresAt() == null ? null : Timestamp.from(op.leaseExpiresAt()));
                q.setString(5, op.reasonCode());
                q.setTimestamp(6, Timestamp.from(op.updatedAt()));
                q.setString(7, op.tenantId());
                q.setObject(8, op.operationId());
                q.setString(9, oldState.name());
                q.setLong(10, oldEpoch);
                if (q.executeUpdate() != 1) {
                    throw new ConflictException("stale provider operation lease", null);
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public Optional<ProviderMapping> providerMapping(
                String tenantId, String providerKind, String billingSubscriptionId) {
            try (PreparedStatement q = c.prepareStatement("SELECT mapping_id, provider_resource_id, "
                    + "created_at, last_verified_at FROM billing.provider_resource_mapping "
                    + "WHERE tenant_id = ? AND provider_kind = ? "
                    + "AND baobab_resource_type = 'BILLING_SUBSCRIPTION' AND baobab_resource_id = ?")) {
                q.setString(1, tenantId);
                q.setString(2, providerKind);
                q.setString(3, billingSubscriptionId);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? Optional.of(new ProviderMapping((UUID) rs.getObject(1),
                            tenantId, providerKind, billingSubscriptionId,
                            rs.getString(2), rs.getTimestamp(3).toInstant(),
                            rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant())) : Optional.empty();
                }
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void insertProviderMapping(ProviderMapping mapping) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.provider_resource_mapping "
                    + "(mapping_id, tenant_id, provider_kind, baobab_resource_type, baobab_resource_id, "
                    + "provider_resource_type, provider_resource_id, created_at, last_verified_at) "
                    + "VALUES (?, ?, ?, 'BILLING_SUBSCRIPTION', ?, 'SUBSCRIPTION', ?, ?, ?)")) {
                q.setObject(1, mapping.mappingId());
                q.setString(2, mapping.tenantId());
                q.setString(3, mapping.providerKind());
                q.setString(4, mapping.billingSubscriptionId());
                q.setString(5, mapping.providerResourceId());
                q.setTimestamp(6, Timestamp.from(mapping.createdAt()));
                q.setTimestamp(7, mapping.lastVerifiedAt() == null ? null : Timestamp.from(mapping.lastVerifiedAt()));
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void appendEvent(OutboxEvent e) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.outbox (event_id, event_type, tenant_id, aggregate_id, "
                    + "envelope, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?)")) {
                q.setObject(1, e.eventId());
                q.setString(2, e.eventType());
                q.setString(3, e.tenantId());
                q.setString(4, e.aggregateId());
                q.setString(5, e.envelopeJson());
                q.setTimestamp(6, Timestamp.from(e.occurredAt()));
                q.executeUpdate();
            } catch (SQLException ex) {
                throw fail(ex);
            }
        }

        @Override
        public void appendAudit(AuditRecord a) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO billing.audit_record (audit_id, tenant_id, occurred_at, "
                    + "resource_type, resource_id, operation, record) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)")) {
                q.setObject(1, a.auditId());
                q.setString(2, a.tenantId());
                q.setTimestamp(3, Timestamp.from(a.occurredAt()));
                q.setString(4, a.resourceType());
                q.setString(5, a.resourceId());
                q.setString(6, a.operation());
                q.setString(7, json(a));
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        List<AuditRecord> audit(String tenantId) {
            try (PreparedStatement q = c.prepareStatement("SELECT record::text FROM billing.audit_record WHERE tenant_id = ? "
                    + "ORDER BY occurred_at, audit_id")) {
                q.setString(1, tenantId);
                List<AuditRecord> out = new ArrayList<>();
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs.getString(1), AuditRecord.class));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        List<OutboxEvent> events(String tenantId) {
            try (PreparedStatement q = c.prepareStatement("SELECT event_id, event_type, aggregate_id, envelope::text, occurred_at "
                    + "FROM billing.outbox WHERE tenant_id = ? ORDER BY occurred_at, event_id")) {
                q.setString(1, tenantId);
                List<OutboxEvent> out = new ArrayList<>();
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        out.add(new OutboxEvent(rs.getObject(1, UUID.class), rs.getString(2), tenantId, rs.getString(3),
                                rs.getString(4), rs.getTimestamp(5).toInstant()));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw fail(e);
            }
        }
    }
}
