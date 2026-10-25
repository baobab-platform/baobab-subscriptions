package com.baobabplatform.subscriptions.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Both wire contract negatives and real PostgreSQL 17 restart/dedup proof. */
public class CpSubscriptionLifecycleInboxTest {
    static final String TENANT = "tn_01k4m7x9q2v6c8r3d5f1h0j4";
    static final String SUB = "sub_01k4z9j3n2";

    public static byte[] event(UUID id, String kind) throws Exception {
        ObjectNode envelope = Json.mapper().createObjectNode();
        envelope.put("specversion", "1.0");
        envelope.put("id", id.toString());
        envelope.put("type", CpSubscriptionLifecycleInbox.PREFIX + kind + ".v1");
        envelope.put("source", CpSubscriptionLifecycleInbox.SOURCE);
        envelope.put("subject", "subscription:" + SUB);
        envelope.put("time", "2026-10-25T12:00:00Z");
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", CpSubscriptionLifecycleInbox.SCHEMA
                + ("classified".equals(kind) ? "subscriptionClassifiedEventData"
                        : "subscriptionLifecycleEventData"));
        envelope.put("baobabscope", "tenant");
        envelope.put("correlationid", UUID.randomUUID().toString());
        envelope.put("tenantid", TENANT);
        ObjectNode payload = envelope.putObject("data");
        payload.put("subscription_id", SUB);
        payload.put("tenant_id", TENANT);
        if ("classified".equals(kind)) {
            payload.put("classification_id", "subcls_01k9zuriinternal");
            payload.put("subscription_type", "INTERNAL");
            payload.put("classification_source", "ADMISSION_DECISION");
            payload.put("classified_at", "2026-10-25T12:00:00Z");
        } else {
            payload.put("product_id", "baobab-xbt");
            payload.put("status", "ACTIVE");
        }
        return Json.mapper().writeValueAsBytes(envelope);
    }

    @Test
    void rejectsUnknownTypeForgedOriginTenantAndInvalidCanonicalPayload() throws Exception {
        UUID id = UUID.randomUUID();
        var original = (ObjectNode) Json.mapper().readTree(event(id, "activated"));
        assertEquals(SUB, CpSubscriptionLifecycleInbox.verify(
                Json.mapper().writeValueAsBytes(original)).subscriptionId());
        var wrongSource = original.deepCopy();
        wrongSource.put("source", "urn:baobab-platform:service:baobab-trade");
        assertThrows(IllegalArgumentException.class,
                () -> CpSubscriptionLifecycleInbox.verify(Json.mapper().writeValueAsBytes(wrongSource)));
        var wrongTenant = original.deepCopy();
        wrongTenant.put("tenantid", "tn_01k9foreignclient");
        assertThrows(IllegalArgumentException.class,
                () -> CpSubscriptionLifecycleInbox.verify(Json.mapper().writeValueAsBytes(wrongTenant)));
        var callerInjected = original.deepCopy();
        ((ObjectNode) callerInjected.path("data")).put("subscription_type", "INTERNAL");
        assertThrows(IllegalArgumentException.class,
                () -> CpSubscriptionLifecycleInbox.verify(Json.mapper().writeValueAsBytes(callerInjected)));
        var unknown = original.deepCopy();
        unknown.put("type", CpSubscriptionLifecycleInbox.PREFIX + "financially-approved.v1");
        assertThrows(IllegalArgumentException.class,
                () -> CpSubscriptionLifecycleInbox.verify(Json.mapper().writeValueAsBytes(unknown)));
        var misdirected = original.deepCopy();
        misdirected.put("subject", "subscription:sub_01kforeignobject");
        assertThrows(IllegalArgumentException.class,
                () -> CpSubscriptionLifecycleInbox.verify(Json.mapper().writeValueAsBytes(misdirected)));
        // Classification is valid notification metadata, never an INTERNAL grant.
        assertEquals(SUB, CpSubscriptionLifecycleInbox.verify(event(UUID.randomUUID(), "classified")).subscriptionId());
    }

    @Test
    void pg17ReceiptIsDurableAndNoProjectionIsFabricated() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"), "PostgreSQL 17 fixture unavailable");
        String user = System.getenv("TEST_DATABASE_USER");
        String password = System.getenv("TEST_DATABASE_PASSWORD");
        UUID id = UUID.randomUUID();
        byte[] raw = event(id, "activated");
        try (PostgresBillingStore store = new PostgresBillingStore(url, user, password)) {
            assertFalse(store.receiveSubscriptionEvent(raw, "baobab-cp-workload").replayed());
            assertTrue(store.receiveSubscriptionEvent(raw, "baobab-cp-workload").replayed());
            assertEquals("PENDING_CP_REFRESH", store.receiveSubscriptionEvent(raw, "baobab-cp-workload").status());
            assertThrows(ConflictException.class,
                    () -> store.receiveSubscriptionEvent(raw, "an-untrusted-client"));
            byte[] changed = (new String(raw, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
            assertThrows(ConflictException.class,
                    () -> store.receiveSubscriptionEvent(changed, "baobab-cp-workload"));
            assertTrue(store.pending(TENANT, 100).stream().anyMatch(n -> n.eventId().equals(id)));
            assertEquals(List.of(), store.pending("tn_01k9siblingenterprise", 100));
            boolean projected = store.transact(tx ->
                    tx.projectionForProductSubscription(TENANT, SUB).isPresent());
            assertFalse(projected, "notifications never create or reclassify the billing projection");
        }
        try (PostgresBillingStore restarted = new PostgresBillingStore(url, user, password)) {
            assertTrue(restarted.receiveSubscriptionEvent(raw, "baobab-cp-workload").replayed());
            assertTrue(restarted.pending(TENANT, 100).stream().anyMatch(n -> n.eventId().equals(id)));
        }
    }
}
