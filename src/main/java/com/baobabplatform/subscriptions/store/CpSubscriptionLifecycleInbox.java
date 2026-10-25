package com.baobabplatform.subscriptions.store;

import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated durable intake of actual Shared product/v1 CP notifications.
 * Never interprets an event as a command, CP revision or INTERNAL grant.
 *
 * <p>The pinned product/v1 payloads do NOT provide monotonic revisions nor
 * full classification/legal/plan terms. Every accepted event is therefore
 * PENDING_CP_REFRESH until an independently authenticated current-CP read
 * protocol is adopted; no state transition may be inferred from delivery order.
 */
public interface CpSubscriptionLifecycleInbox {
    String SOURCE = "urn:baobab-platform:service:baobab-cp";
    String PREFIX = "com.baobab-platform.product.subscription.";
    String SUFFIX = ".v1";
    String SCHEMA = Contracts.BASE + "product/v1/events.schema.json#/$defs/";

    Map<String, String> TYPES = Map.of(
            "created", "SubscriptionLifecycle",
            "activated", "SubscriptionLifecycle",
            "suspended", "SubscriptionLifecycle",
            "cancelled", "SubscriptionLifecycle",
            "classified", "SubscriptionClassified");

    record Notification(UUID eventId, String type, String tenantId,
                        String subscriptionId, String source) {
    }

    record Receipt(UUID eventId, boolean replayed, String status) {
    }

    /** Durably acknowledge but do not apply. The client ID MUST come from a verified IAM token. */
    Receipt receiveSubscriptionEvent(byte[] raw, String authenticatedClientId);

    /** Pending notifications for a specific tenant only; no cross-tenant enumerations. */
    java.util.List<Notification> pending(String tenantId, int limit);

    static Notification verify(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 64 * 1024) {
            throw new IllegalArgumentException("invalid event body length");
        }
        final JsonNode event;
        try {
            event = Json.mapper().readTree(raw);
        } catch (java.io.IOException malformed) {
            throw new IllegalArgumentException("malformed CP notification");
        }
        if (!Contracts.validEntry("envelope", event)
                || !SOURCE.equals(event.path("source").asText())
                || !"tenant".equals(event.path("baobabscope").asText())
                || !"application/json".equals(event.path("datacontenttype").asText())
                || !"1.0".equals(event.path("specversion").asText())) {
            throw new IllegalArgumentException("invalid CP notification envelope");
        }
        String type = event.path("type").asText();
        if (!type.startsWith(PREFIX) || !type.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("unregistered CP subscription event type");
        }
        String action = type.substring(PREFIX.length(), type.length() - SUFFIX.length());
        String def = TYPES.get(action);
        if (def == null || !(SCHEMA + (def.equals("SubscriptionClassified")
                    ? "subscriptionClassifiedEventData" : "subscriptionLifecycleEventData"))
                    .equals(event.path("dataschema").asText())
                || !Contracts.validEntry(def, event.path("data"))) {
            throw new IllegalArgumentException("unregistered event payload or schema");
        }
        String tenant = event.path("tenantid").asText();
        String subject = event.path("data").path("subscription_id").asText();
        if (tenant.isBlank() || !tenant.equals(event.path("data").path("tenant_id").asText())
                || !("subscription:" + subject).equals(event.path("subject").asText())) {
            throw new IllegalArgumentException("inconsistent tenant or canonical subject");
        }
        try {
            Instant.parse(event.path("time").asText());
            UUID.fromString(event.path("correlationid").asText());
            return new Notification(UUID.fromString(event.path("id").asText()),
                    type, tenant, subject, SOURCE);
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid CP event identity or timestamp");
        }
    }
}
