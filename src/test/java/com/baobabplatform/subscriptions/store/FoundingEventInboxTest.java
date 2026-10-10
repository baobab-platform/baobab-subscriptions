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
            assertFalse(first.receive(body).replayed());
            assertTrue(first.receive(body).replayed());
            // Valid JSON with different raw bytes under the SAME event ID:
            // the durable inbox must reject it rather than acknowledging.
            byte[] altered = (new String(body, java.nio.charset.StandardCharsets.UTF_8) + " ")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class,
                    () -> first.receive(altered),
                    "changed event replay should never be silently accepted");
        }
        try (PostgresBillingStore restarted = new PostgresBillingStore(url, user, pass)) {
            assertTrue(restarted.receive(body).replayed(),
                    "received event is durable across service restarts");
        }
    }
}
