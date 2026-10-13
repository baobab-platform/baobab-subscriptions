package com.baobabplatform.subscriptions.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FoundingEventInboxTest {
    private static byte[] event(UUID id, UUID aggregate, String status) throws Exception {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("specversion", "1.0");
        root.put("datacontenttype", "application/json");
        root.put("baobabscope", "platform");
        root.put("time", java.time.Instant.now().toString());
        root.put("correlationid", UUID.randomUUID().toString());
        root.put("id", id.toString());
        root.put("source", "urn:baobab-platform:service:baobab-cp");
        root.put("subject", "founding-governance/" + aggregate);
        root.put("type", "com.baobab-platform.control-plane.founding-sponsorship.suspended.v1");
        root.put("dataschema", "https://contracts.baobab-platform.com/admission/v2/"
                + "founding-lifecycle-events.schema.json#/$defs/SponsorshipSuspended");
        root.putObject("data").put("sponsorship_id", aggregate.toString())
                .put("operating_organisation_id", UUID.randomUUID().toString())
                .put("status", status);
        return Json.mapper().writeValueAsBytes(root);
    }

    @Test
    void rejectsSpoofedOrMismatchedEvents() throws Exception {
        UUID id = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        var canonical = FoundingEventInbox.parse(event(id, aggregate, "SUSPENDED"));
        assertEquals(id, FoundingEventInbox.verify(canonical));
        assertThrows(IllegalArgumentException.class,
                () -> FoundingEventInbox.verify(FoundingEventInbox.parse(event(id, aggregate, "ACTIVE"))));
        ((ObjectNode) canonical).put("source", "urn:untrusted");
        assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.verify(canonical));
    }

    @Test
    void closedOwnerEnvelopeAndPayloadRejectExtraFieldsLaxUuidsAndAmbiguousJson() throws Exception {
        UUID id = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        ObjectNode canonical = (ObjectNode) FoundingEventInbox.parse(event(id, aggregate, "SUSPENDED"));
        for (String field : java.util.List.of("authority_granted", "tenantid")) {
            ObjectNode changed = canonical.deepCopy();
            changed.put(field, "tn_synthetic123");
            assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.verify(changed));
        }
        ObjectNode shortId = canonical.deepCopy();
        shortId.put("id", "1-1-1-1-1");
        assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.verify(shortId));
        ObjectNode wrongType = canonical.deepCopy();
        wrongType.put("specversion", 1.0);
        assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.verify(wrongType));
        ObjectNode extraPayload = canonical.deepCopy();
        ((ObjectNode) extraPayload.path("data")).put("provider_override", true);
        assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.verify(extraPayload));
        String raw = Json.mapper().writeValueAsString(canonical);
        for (String ambiguous : java.util.List.of(raw + " {}", raw.replaceFirst("\\{", "{\"id\":\"foreign\","))) {
            assertThrows(IllegalArgumentException.class, () -> FoundingEventInbox.parse(
                    ambiguous.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    void postgresReceiptSurvivesRestartAndDetectsChangedReplay() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && url.startsWith("jdbc:postgresql://"),
                "PostgreSQL staging integration database unavailable");
        UUID id = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        byte[] body = event(id, grant, "SUSPENDED");
        String user = System.getenv("TEST_DATABASE_USER");
        String pass = System.getenv("TEST_DATABASE_PASSWORD");
        try (PostgresBillingStore first = new PostgresBillingStore(url, user, pass)) {
            // Unknown wire fields must never become durable receipt facts.
            ObjectNode malformed = (ObjectNode) FoundingEventInbox.parse(body);
            UUID rejectedId = UUID.randomUUID();
            malformed.put("id", rejectedId.toString());
            malformed.put("authority_granted", true);
            assertThrows(IllegalArgumentException.class,
                    () -> first.receive(Json.mapper().writeValueAsBytes(malformed), "baobab-cp"));
            try (var connection = java.sql.DriverManager.getConnection(url, user, pass);
                 var query = connection.prepareStatement("SELECT count(*) FROM billing.founding_event_inbox WHERE event_id = ?")) {
                query.setObject(1, rejectedId);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
            assertFalse(first.receive(body, "baobab-cp").replayed());
            assertTrue(first.receive(body, "baobab-cp").replayed());
            assertThrows(IllegalArgumentException.class,
                    () -> first.receive(body, "untrusted-other-engine"),
                    "the same event must not be acknowledged under another authenticated workload");

            // Valid JSON with different raw bytes under the SAME event ID:
            // the durable inbox must reject it rather than acknowledging.
            byte[] altered = (new String(body, java.nio.charset.StandardCharsets.UTF_8) + " ")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class,
                    () -> first.receive(altered, "baobab-cp"),
                    "changed event replay should never be silently accepted");
        }
        try (PostgresBillingStore restarted = new PostgresBillingStore(url, user, pass)) {
            assertTrue(restarted.receive(body, "baobab-cp").replayed(),
                    "received event is durable across service restarts");
        }
    }
}
