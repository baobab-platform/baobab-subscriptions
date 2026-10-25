package com.baobabplatform.subscriptions.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real PostgreSQL V5 migration, not an in-memory approximation.
 *
 * <p>All fixtures are synthetic and rolled back; the suite cannot grant a
 * ProductSubscription, resolve its classification, or move money.
 */
class ProviderMigrationTest {
    @Test
    void compositeTenantFenceAndProviderIdentityUniqueness() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"),
                "TEST_DATABASE_URL not set; PostgreSQL integration is required for release");
        String user = System.getenv("TEST_DATABASE_USER");
        String password = System.getenv("TEST_DATABASE_PASSWORD");
        try (PostgresBillingStore migrated = new PostgresBillingStore(url, user, password);
             Connection c = DriverManager.getConnection(url, user, password)) {
            assertEquals(17, c.getMetaData().getDatabaseMajorVersion(),
                    "Baobab's Subscriptions persistence must be tested against PostgreSQL 17");
            c.setAutoCommit(false);
            String id = "synthetic-bs-" + UUID.randomUUID();
            String tenant = "synthetic-tenant-" + UUID.randomUUID();
            String sibling = "synthetic-sibling-" + UUID.randomUUID();
            try {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO billing.projection "
                      + "(billing_subscription_id, tenant_id, product_subscription_id, version,"
                      + " document, updated_at, authoritative_revision)"
                      + " VALUES (?, ?, ?, 1, '{}'::jsonb, now(), 1)")) {
                    p.setString(1, id);
                    p.setString(2, tenant);
                    p.setString(3, "synthetic-product-" + UUID.randomUUID());
                    p.executeUpdate();
                }
                insertOperation(c, UUID.randomUUID(), tenant, id, "same-key");
                Savepoint beforeSibling = c.setSavepoint();
                assertThrows(SQLException.class,
                        () -> insertOperation(c, UUID.randomUUID(), sibling, id, "other-tenant-key"),
                        "Provider operation must not reference another tenant's projection");
                c.rollback(beforeSibling);

                Savepoint beforeRepeat = c.setSavepoint();
                assertThrows(SQLException.class,
                        () -> insertOperation(c, UUID.randomUUID(), tenant, id, "same-key"),
                        "Idempotency key cannot create duplicate provider operations in a tenant");
                c.rollback(beforeRepeat);

                insertMapping(c, UUID.randomUUID(), tenant, id, "external-a");
                Savepoint beforeAlias = c.setSavepoint();
                assertThrows(SQLException.class,
                        () -> insertMapping(c, UUID.randomUUID(), tenant, id, "external-b"),
                        "One Baobab subscription cannot silently acquire a second active provider ID");
                c.rollback(beforeAlias);
                Savepoint beforeDuplicateProvider = c.setSavepoint();
                assertThrows(SQLException.class,
                        () -> insertMapping(c, UUID.randomUUID(), tenant, "other-baobab-id", "external-a"),
                        "One provider object cannot map to two Baobab resources within a tenant");
                c.rollback(beforeDuplicateProvider);
            } finally {
                // The test never permanently creates a provider identity or operation.
                c.rollback();
            }
        }
    }

    private static void insertOperation(Connection c, UUID operationId, String tenant,
                                        String subscription, String key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO billing.provider_operation "
              + "(operation_id, tenant_id, billing_subscription_id, provider_kind, "
              + "operation_kind, idempotency_key, request_sha256, authoritative_revision)"
              + " VALUES (?, ?, ?, 'KILLBILL', 'ENSURE', ?, ?, 1)")) {
            p.setObject(1, operationId);
            p.setString(2, tenant);
            p.setString(3, subscription);
            p.setString(4, key);
            p.setString(5, "a".repeat(64));
            p.executeUpdate();
        }
    }

    private static void insertMapping(Connection c, UUID mappingId, String tenant,
                                      String canonicalId, String nativeId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO billing.provider_resource_mapping "
              + "(mapping_id, tenant_id, provider_kind, baobab_resource_type, "
              + "baobab_resource_id, provider_resource_type, provider_resource_id)"
              + " VALUES (?, ?, 'KILLBILL', 'BILLING_SUBSCRIPTION', ?, 'SUBSCRIPTION', ?)")) {
            p.setObject(1, mappingId);
            p.setString(2, tenant);
            p.setString(3, canonicalId);
            p.setString(4, nativeId);
            p.executeUpdate();
        }
    }
}
