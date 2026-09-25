package com.baobabplatform.subscriptions.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {
    private static Map<String, String> base(String environment) {
        Map<String, String> env = new HashMap<>();
        env.put("BAOBAB_ENVIRONMENT", environment);
        env.put("WORKLOAD_ISSUER", "https://iam.baobab.test/realms/baobab");
        env.put("WORKLOAD_JWKS_URI", "https://iam.baobab.test/realms/baobab/protocol/openid-connect/certs");
        return env;
    }

    @Test
    void developmentDefaults() {
        Config config = Config.fromEnvironment(base("development"));
        assertEquals(8080, config.httpPort());
        assertEquals("baobab-subscriptions", config.workloadAudience());
        assertEquals(java.util.Set.of("baobab-control-plane"), config.allowedClients());
        assertEquals(null, config.databaseUrl());
    }

    @Test
    void theTemporaryProviderIsRefusedInProduction() {
        Map<String, String> env = base("production");
        env.put("DATABASE_URL", "jdbc:postgresql://db:5432/billing");
        ConfigException e = assertThrows(ConfigException.class, () -> Config.fromEnvironment(env));
        assertTrue(e.getMessage().contains("refused in production"), e.getMessage());
    }

    @Test
    void workloadIdentityIsRequired() {
        Map<String, String> env = base("development");
        env.remove("WORKLOAD_ISSUER");
        env.remove("WORKLOAD_JWKS_URI");
        ConfigException e = assertThrows(ConfigException.class, () -> Config.fromEnvironment(env));
        assertTrue(e.getMessage().contains("WORKLOAD_ISSUER is required") && e.getMessage().contains("WORKLOAD_JWKS_URI is required"));
    }

    @Test
    void stagingNeedsADatabaseAndHttpsKeys() {
        Map<String, String> env = base("staging");
        env.put("WORKLOAD_JWKS_URI", "http://iam/certs");
        ConfigException e = assertThrows(ConfigException.class, () -> Config.fromEnvironment(env));
        assertTrue(e.getMessage().contains("DATABASE_URL is required") && e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    void onlyTheTemporaryProviderExists() {
        Map<String, String> env = base("development");
        env.put("BILLING_PROVIDER", "killbill");
        assertTrue(assertThrows(ConfigException.class, () -> Config.fromEnvironment(env)).getMessage().contains("not implemented"));
    }

    @Test
    void thePasswordIsNeverPrinted() {
        Map<String, String> env = base("development");
        env.put("DATABASE_URL", "jdbc:postgresql://db:5432/billing");
        env.put("DATABASE_PASSWORD", "s3cret-value");
        assertFalse(Config.fromEnvironment(env).toString().contains("s3cret-value"));
    }
}
