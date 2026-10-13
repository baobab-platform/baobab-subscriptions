package com.baobabplatform.subscriptions.contract;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline maintainer/build probe of the real packaged JAR and runtime libs.
 * Synthetic wire validity is never workload admission or billing authority. */
public final class ContractProbe {
    private ContractProbe() { }

    public static int conformance(JsonNode cases) {
        if (!cases.isArray() || cases.isEmpty()) throw new IllegalArgumentException("Invalid synthetic vectors");
        for (JsonNode test : cases) {
            if (Contracts.validEntry(test.path("contract").asText(), test.path("instance"))
                    != test.path("valid").asBoolean()) {
                throw new IllegalStateException("Pinned owner wire conformance differs");
            }
        }
        return cases.size();
    }

    public static void main(String[] args) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            // Prove schema resources do not depend on the caller's TCCL.
            thread.setContextClassLoader(new ClassLoader(null) { });
            Contracts.verifyBundle();
            if (args.length == 1 && args[0].equals("contract-self-test")) {
                // Assert formats, not only required fields or grammar patterns.
                JsonNode invalid = Json.mapper().readTree("""
                    {"tenant_id":"tn_synthetic123","product_subscription_id":"sub_synthetic123",
                     "classification_reference":"synthetic/adm/123","eligible":true,
                     "evaluated_at":"2026-02-30T00:00:00Z","expires_at":"2026-10-13T00:00:03Z",
                     "policy_reference":"ADR-BCP-017/PEO-02D"}
                    """);
                if (Contracts.validEntry("internal_authority", invalid)) {
                    throw new IllegalStateException("Date-time format was not asserted");
                }
                System.out.println("Pinned JAR resources and offline formats verified");
            } else if (args.length == 2 && args[0].equals("contract-conformance")) {
                int count = conformance(Json.mapper().readTree(Files.readAllBytes(Path.of(args[1]))));
                System.out.println("Pinned JAR conformance: " + count + " synthetic cases");
            } else {
                throw new IllegalArgumentException("Expected offline contract probe arguments");
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
