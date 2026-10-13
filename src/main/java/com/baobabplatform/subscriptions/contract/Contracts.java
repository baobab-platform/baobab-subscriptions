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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

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

    private static final JsonNode MANIFEST = manifest();
    private static final Map<String, String> RESOURCES = resources();
    private static final SchemaRegistry REGISTRY = ownLoader(() -> SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(RESOURCES)
                    .schemaLoader(loader -> loader.fetchRemoteResources(false))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())));
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private Contracts() {
    }

    /** A definition within a contract file, e.g. def(BILLING, "BillingProjection"). */
    public static String def(String file, String definition) {
        return file + "#/$defs/" + definition;
    }

    /** Problems with instance against ref (a path under contracts/, optionally with a fragment). Empty when valid. */
    public static List<String> problems(String ref, JsonNode instance) {
        // Only package-owned schema files are addressable. Relative/absolute
        // arbitrary file and network lookups cannot enter the registry.
        if (!RESOURCES.containsKey(BASE + ref.split("#", 2)[0])) {
            throw new IllegalArgumentException("Undeclared contract reference");
        }
        return ownLoader(() -> schema(ref).validate(instance.toString(), InputFormat.JSON).stream()
                .map(Contracts::describe)
                .sorted()
                .toList());
    }

    /** Problems with a value, serialised as the API serialises it. */
    public static List<String> problemsWithValue(String ref, Object value) {
        return problems(ref, Json.mapper().valueToTree(value));
    }

    private static String describe(Error message) {
        // A validator message/instance path can reflect input or property names.
        return "contract violation at " + message.getSchemaLocation();
    }

    private static Schema schema(String ref) {
        return SCHEMAS.computeIfAbsent(ref, r -> REGISTRY.getSchema(SchemaLocation.of(BASE + r)));
    }

    public static boolean validEntry(String name, JsonNode instance) {
        JsonNode entry = MANIFEST.path("entries").get(name);
        if (entry == null) throw new IllegalArgumentException("Undeclared contract entry");
        return problems(entry.asText().substring(BASE.length()), instance).isEmpty();
    }

    /** Original bytes embedded in the packaged JAR, never a mutable checkout. */
    public static byte[] resource(String name) {
        try (InputStream in = Contracts.class.getResourceAsStream("/contracts/" + name)) {
            if (in == null) throw new IllegalStateException("Pinned contract resource missing");
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Pinned contract resource unavailable", failure);
        }
    }

    private static JsonNode manifest() {
        try {
            return Json.mapper().readTree(resource("provenance.json"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Pinned contract provenance unavailable", failure);
        }
    }

    private static Map<String, String> resources() {
        try {
            Map<String, String> out = new LinkedHashMap<>();
            var files = MANIFEST.path("files").properties().iterator();
            while (files.hasNext()) {
                var file = files.next();
                byte[] raw = resource(file.getKey());
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
                if (!hash.equals(file.getValue().asText())) {
                    throw new IllegalStateException("Pinned contract resource integrity failed");
                }
                if (file.getKey().endsWith(".schema.json")) {
                    JsonNode doc = Json.mapper().readTree(raw);
                    out.put(doc.path("$id").asText(), new String(raw, StandardCharsets.UTF_8));
                }
            }
            return Map.copyOf(out);
        } catch (Exception failure) {
            throw new IllegalStateException("Pinned contract registry unavailable", failure);
        }
    }

    private static <T> T ownLoader(Supplier<T> task) {
        // Networknt's meta-schema resources can consult TCCL. Restrict the
        // temporary context to this package's loader and always restore callers.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(Contracts.class.getClassLoader());
            return task.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static JsonNode provenance() { return MANIFEST.deepCopy(); }

    /** Startup/build check, not a billing capability or INTERNAL grant. */
    public static void verifyBundle() {
        ownLoader(() -> {
            MANIFEST.path("entries").elements().forEachRemaining(
                    entry -> schema(entry.asText().substring(BASE.length())));
            return null;
        });
    }
}
