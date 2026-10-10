package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;

/**
 * PEO-02E durable receiver contract. Receipts are audit/invalidation hints;
 * they NEVER grant INTERNAL status or override the live Control Plane PDP.
 */
public interface FoundingEventInbox {
    record Receipt(UUID eventId, boolean replayed) {
    }

    Map<String, String> ALLOWED = Map.of(
            "founding-sponsorship.activated", "SponsorshipActivated:ACTIVE",
            "founding-sponsorship.suspended", "SponsorshipSuspended:SUSPENDED",
            "founding-sponsorship.revoked", "SponsorshipRevoked:REVOKED",
            "founding-sponsorship.expired", "SponsorshipExpired:EXPIRED",
            "founding-documentary-deferral.activated", "DeferralActivated:ACTIVE",
            "founding-documentary-deferral.revoked", "DeferralRevoked:REVOKED",
            "founding-documentary-deferral.expired", "DeferralExpired:EXPIRED");

    Receipt receive(byte[] rawEvent);

    /** Reject corrupted/misattributed events BEFORE any durable receipt. */
    static UUID verify(JsonNode event) {
        if (!"1.0".equals(event.path("specversion").asText())
                || !"urn:baobab-platform:service:baobab-cp".equals(event.path("source").asText())
                || !event.path("data").isObject()) {
            throw new IllegalArgumentException("invalid founding CloudEvent source or envelope");
        }
        String type = event.path("type").asText();
        String prefix = "com.baobab-platform.control-plane.";
        String suffix = ".v1";
        if (!type.startsWith(prefix) || !type.endsWith(suffix)) {
            throw new IllegalArgumentException("non-canonical founding event type");
        }
        String kind = type.substring(prefix.length(), type.length() - suffix.length());
        String rule = ALLOWED.get(kind);
        if (rule == null) {
            throw new IllegalArgumentException("unregistered founding event type");
        }
        String[] parts = rule.split(":", 2);
        String expectedSchema = "https://contracts.baobab-platform.com/admission/v2/"
                + "founding-lifecycle-events.schema.json#/$defs/" + parts[0];
        if (!expectedSchema.equals(event.path("dataschema").asText())
                || !parts[1].equals(event.path("data").path("status").asText())) {
            throw new IllegalArgumentException("founding type/data/schema mismatch");
        }
        String grant = event.path("data").path(kind.startsWith("founding-sponsorship.")
                ? "sponsorship_id" : "deferral_id").asText();
        String org = event.path("data").path(kind.startsWith("founding-sponsorship.")
                ? "operating_organisation_id" : "organisation_id").asText();
        UUID id, aggregate;
        try {
            id = UUID.fromString(event.path("id").asText());
            aggregate = UUID.fromString(grant);
            UUID.fromString(org);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid founding event ids", invalid);
        }
        if (!("founding-governance/" + aggregate).equals(event.path("subject").asText())) {
            throw new IllegalArgumentException("founding event subject mismatch");
        }
        return id;
    }

    static JsonNode parse(byte[] rawEvent) {
        try {
            return Json.mapper().readTree(rawEvent);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid JSON event", invalid);
        }
    }
}
