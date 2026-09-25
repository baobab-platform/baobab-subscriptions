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
public final class PostgresBillingStore implements BillingStore {
    private static final String[] MIGRATIONS = {"db/V1__billing.sql"};
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

    @Override
    public List<OutboxEvent> events(String tenantId) {
        return transact(tx -> ((SqlTx) tx).events(tenantId));
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
                    + "product_subscription_id, version, document, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?)")) {
                q.setString(1, p.billingSubscriptionId());
                q.setString(2, p.tenantId());
                q.setString(3, p.productSubscriptionId());
                q.setLong(4, p.version());
                q.setString(5, json(p));
                q.setTimestamp(6, Timestamp.from(p.updatedAt()));
                q.executeUpdate();
            } catch (SQLException e) {
                throw fail(e);
            }
        }

        @Override
        public void updateProjection(Projection p, long expectedVersion) {
            try (PreparedStatement q = c.prepareStatement("UPDATE billing.projection SET version = ?, document = ?::jsonb, "
                    + "updated_at = ? WHERE tenant_id = ? AND billing_subscription_id = ? AND version = ?")) {
                q.setLong(1, p.version());
                q.setString(2, json(p));
                q.setTimestamp(3, Timestamp.from(p.updatedAt()));
                q.setString(4, p.tenantId());
                q.setString(5, p.billingSubscriptionId());
                q.setLong(6, expectedVersion);
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
