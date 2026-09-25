package com.baobabplatform.subscriptions.contract;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime validation against the baobab-platform/shared contracts this engine
 * pins (src/main/resources/contracts, byte-for-byte copies of the Shared commit
 * in contracts.lock.yaml). Schemas resolve by their canonical $id from the
 * classpath, so cross-file $refs work offline.
 */
public final class Contracts {
    public static final String BASE = "https://contracts.baobab-platform.com/";
    public static final String BILLING = "subscriptions/v1/billing.schema.json";
    public static final String EVENTS = "subscriptions/v1/events.schema.json";
    public static final String ENVELOPE = "events/v1/envelope.schema.json";
    public static final String PROBLEM = "errors/v1/problem-details.schema.json";

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE, "classpath:contracts/")));
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build();
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();

    private Contracts() {
    }

    /** A definition within a contract file, e.g. def(BILLING, "BillingProjection"). */
    public static String def(String file, String definition) {
        return file + "#/$defs/" + definition;
    }

    /** Problems with instance against ref (a path under contracts/, optionally with a fragment). Empty when valid. */
    public static List<String> problems(String ref, JsonNode instance) {
        JsonSchema schema = SCHEMAS.computeIfAbsent(ref, r -> FACTORY.getSchema(SchemaLocation.of(BASE + r), CONFIG));
        return schema.validate(instance).stream()
                .map(Contracts::describe)
                .sorted()
                .toList();
    }

    /** Problems with a value, serialised as the API serialises it. */
    public static List<String> problemsWithValue(String ref, Object value) {
        return problems(ref, Json.mapper().valueToTree(value));
    }

    private static String describe(ValidationMessage message) {
        String at = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        return (at.isEmpty() ? "$" : at) + ": " + message.getMessage();
    }
}
