package com.baobabplatform.subscriptions.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL lease-fenced outbox. Database time is the only lease clock.
 * No transport I/O occurs under a database lock or transaction.
 */
public final class PostgresOutboxDeliveryQueue implements OutboxDeliveryQueue {
    private static final int MAX_ATTEMPTS = 8;
    private final DataSource source;

    public PostgresOutboxDeliveryQueue(DataSource source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private <T> T transact(SqlWork<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("outbox database unavailable", ex);
        }
    }

    private static void validTenant(String tenant) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 200) {
            throw new IllegalArgumentException("tenant required");
        }
    }

    @Override
    public Optional<Claim> claim(String tenantId, String worker, Duration lease) {
        validTenant(tenantId);
        if (worker == null || !worker.matches("[A-Za-z0-9._:-]{3,128}")) {
            throw new IllegalArgumentException("invalid worker identity");
        }
        if (lease == null || lease.compareTo(Duration.ofSeconds(5)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0 || lease.getNano() != 0) {
            throw new IllegalArgumentException("lease must be 5–300 whole seconds");
        }
        return transact(connection -> {
            // An exhausted worker that died before recording its failure must
            // not be reclaimed indefinitely. It requires operator review.
            try (PreparedStatement sweep = connection.prepareStatement("""
                    UPDATE billing.outbox SET publication_status='QUARANTINED',
                        failure_code='ATTEMPTS_EXHAUSTED',
                        first_failure_at=COALESCE(first_failure_at, now()),
                        last_failure_at=now(), lease_owner=NULL, lease_expires_at=NULL
                    WHERE tenant_id=? AND publication_status='IN_FLIGHT'
                        AND lease_expires_at<=now() AND attempt_count>=?
                    """)) {
                sweep.setString(1, tenantId);
                sweep.setInt(2, MAX_ATTEMPTS);
                sweep.executeUpdate();
            }
            try (PreparedStatement claim = connection.prepareStatement("""
                    WITH next_event AS (
                        SELECT event_id FROM billing.outbox
                        WHERE tenant_id=? AND attempt_count<?
                          AND ((publication_status IN ('PENDING','RETRY') AND next_attempt_at<=now())
                            OR (publication_status='IN_FLIGHT' AND lease_expires_at<=now()))
                        ORDER BY occurred_at, event_id
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE billing.outbox AS event SET publication_status='IN_FLIGHT',
                        lease_owner=?, lease_epoch=event.lease_epoch+1,
                        lease_expires_at=now()+(? * INTERVAL '1 second'),
                        attempt_count=event.attempt_count+1
                    FROM next_event WHERE event.event_id=next_event.event_id
                    RETURNING event.event_id,event.event_type,event.tenant_id,event.aggregate_id,
                        event.envelope::text,event.occurred_at,event.lease_epoch,
                        event.attempt_count,event.lease_expires_at
                    """)) {
                claim.setString(1, tenantId);
                claim.setInt(2, MAX_ATTEMPTS);
                claim.setString(3, worker);
                claim.setLong(4, lease.toSeconds());
                try (ResultSet rs = claim.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    OutboxEvent event = new OutboxEvent(
                            rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getTimestamp(6).toInstant());
                    return Optional.of(new Claim(event, worker, rs.getLong(7),
                            rs.getInt(8), rs.getTimestamp(9).toInstant()));
                }
            }
        });
    }

    @Override
    public boolean published(Claim claim) {
        java.util.Objects.requireNonNull(claim);
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE billing.outbox SET publication_status='PUBLISHED',
                        published_at=now(), lease_owner=NULL, lease_expires_at=NULL,
                        failure_code=NULL
                    WHERE event_id=? AND tenant_id=? AND lease_epoch=?
                        AND lease_owner=? AND publication_status='IN_FLIGHT'
                        AND lease_expires_at>now()
                    """)) {
                bindClaim(statement, claim);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean failed(Claim claim, String safeFailureCode, boolean retryable) {
        java.util.Objects.requireNonNull(claim);
        if (safeFailureCode == null || !safeFailureCode.matches("[A-Z][A-Z0-9_]{1,47}")) {
            throw new IllegalArgumentException("failure code must be a bounded non-sensitive category");
        }
        boolean scheduleRetry = retryable && claim.attempt() < MAX_ATTEMPTS;
        long retrySeconds = Math.min(3600L, 5L << Math.min(10, Math.max(0, claim.attempt() - 1)))
                + Math.floorMod(claim.event().eventId().hashCode(), 7);
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE billing.outbox SET publication_status=?,
                        next_attempt_at=now()+(? * INTERVAL '1 second'),
                        lease_owner=NULL, lease_expires_at=NULL,
                        failure_code=?, first_failure_at=COALESCE(first_failure_at,now()),
                        last_failure_at=now()
                    WHERE event_id=? AND tenant_id=? AND lease_epoch=?
                        AND lease_owner=? AND publication_status='IN_FLIGHT'
                        AND lease_expires_at>now()
                    """)) {
                statement.setString(1, scheduleRetry ? "RETRY" : "QUARANTINED");
                statement.setLong(2, retrySeconds);
                statement.setString(3, safeFailureCode);
                statement.setObject(4, claim.event().eventId());
                statement.setString(5, claim.event().tenantId());
                statement.setLong(6, claim.leaseEpoch());
                statement.setString(7, claim.worker());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static void bindClaim(PreparedStatement statement, Claim claim) throws SQLException {
        statement.setObject(1, claim.event().eventId());
        statement.setString(2, claim.event().tenantId());
        statement.setLong(3, claim.leaseEpoch());
        statement.setString(4, claim.worker());
    }

    @Override
    public Health health(String tenantId) {
        validTenant(tenantId);
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                      count(*) FILTER (WHERE publication_status='PENDING'),
                      count(*) FILTER (WHERE publication_status='IN_FLIGHT'),
                      count(*) FILTER (WHERE publication_status='RETRY'),
                      count(*) FILTER (WHERE publication_status='QUARANTINED'),
                      COALESCE(sum(attempt_count), 0),
                      COALESCE(GREATEST(0, EXTRACT(EPOCH FROM now()-min(occurred_at)
                        FILTER (WHERE publication_status<>'PUBLISHED')))::bigint,0)
                    FROM billing.outbox WHERE tenant_id=?
                    """)) {
                statement.setString(1, tenantId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return new Health(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6));
                }
            }
        });
    }
}
