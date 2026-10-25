package com.baobabplatform.subscriptions.provider;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Explicit tenant-to-provider bindings loaded from a local deployment file.
 * The file contains ONLY secret file paths, never inline API credentials.
 * Deployments must protect it and its referenced files separately.
 */
public final class KillBillTenantRegistry {
    private KillBillTenantRegistry() {
    }

    public static KillBillAdapter load(Path file) {
        try {
            JsonNode data = Json.mapper().readTree(Files.readAllBytes(file));
            if (!data.isArray() || data.isEmpty() || data.size() > 100) {
                throw new IllegalArgumentException("an explicit bounded Kill Bill tenant registry is required");
            }
            Map<String, KillBillAdapter.TenantEndpoint> bindings = new HashMap<>();
            for (JsonNode row : data) {
                if (!row.isObject() || row.size() != 6) {
                    throw new IllegalArgumentException("Kill Bill tenant bindings must contain six approved fields");
                }
                String tenant = required(row, "tenant_id");
                if (!tenant.matches("tn_[a-z0-9]{3,63}")) {
                    throw new IllegalArgumentException("invalid CP-minted tenant ID");
                }
                KillBillAdapter.TenantEndpoint config = new KillBillAdapter.TenantEndpoint(
                        URI.create(required(row, "base_url")),
                        Path.of(required(row, "username_file")), Path.of(required(row, "password_file")),
                        Path.of(required(row, "api_key_file")), Path.of(required(row, "api_secret_file")));
                if (bindings.putIfAbsent(tenant, config) != null) {
                    throw new IllegalArgumentException("duplicate Kill Bill tenant binding");
                }
            }
            return new KillBillAdapter(bindings);
        } catch (IOException error) {
            throw new IllegalStateException("Kill Bill registry not available", error);
        }
    }

    private static String required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalArgumentException("missing required tenant binding field");
        }
        return v.asText();
    }
}
