package com.baobabplatform.subscriptions.contract;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime validation against the baobab-platform/shared contracts this engine
 * pins (src/main/resources/contracts, byte-for-byte copies of the Shared commit
 * in contracts.lock.yaml). Schemas resolve by their canonical $id from the
 * classpath, so cross-file $refs work offline.
 *
 * <p>json-schema-validator 3.x reads JSON with Jackson 3 ({@code tools.jackson}),
 * while this engine's own JSON stays on Jackson 2; an instance therefore
 * reaches the validator as JSON text, never as a Jackson 2 tree.
 */
public final class Contracts {
    public static final String BASE = "https://contracts.baobab-platform.com/";
    public static final String BILLING = "subscriptions/v1/billing.schema.json";
    public static final String EVENTS = "subscriptions/v1/events.schema.json";
    public static final String ENVELOPE = "events/v1/envelope.schema.json";
    public static final String PROBLEM = "errors/v1/problem-details.schema.json";

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaIdResolvers(resolvers -> resolvers.mapPrefix(BASE, "classpath:contracts/"))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private Contracts() {
    }

    /** A definition within a contract file, e.g. def(BILLING, "BillingProjection"). */
    public static String def(String file, String definition) {
        return file + "#/$defs/" + definition;
    }

    /** Problems with instance against ref (a path under contracts/, optionally with a fragment). Empty when valid. */
    public static List<String> problems(String ref, JsonNode instance) {
        Schema schema = SCHEMAS.computeIfAbsent(ref, r -> REGISTRY.getSchema(SchemaLocation.of(BASE + r)));
        return schema.validate(instance.toString(), InputFormat.JSON).stream()
                .map(Contracts::describe)
                .sorted()
                .toList();
    }

    /** Problems with a value, serialised as the API serialises it. */
    public static List<String> problemsWithValue(String ref, Object value) {
        return problems(ref, Json.mapper().valueToTree(value));
    }

    private static String describe(Error message) {
        String at = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        return (at.isEmpty() ? "$" : at) + ": " + message.getMessage();
    }
}
