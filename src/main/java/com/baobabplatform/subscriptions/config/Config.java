package com.baobabplatform.subscriptions.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validated service configuration, read once from the environment.
 *
 * <p>Callers authenticate with Baobab workload identity only: a JWT from the
 * configured issuer, for this engine's audience, whose client is allowed. There
 * is no unauthenticated mode and no static bearer secret. The temporary billing
 * provider is refused in production (ADR-SUB-0001 section 6).
 */
public record Config(
        Environment environment,
        int httpPort,
        Duration shutdownGrace,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        String billingProvider,
        URI workloadIssuer,
        URI workloadJwksUri,
        String workloadAudience,
        Set<String> allowedClients) {

    /** The deployment environment. */
    public enum Environment {
        DEVELOPMENT, INTEGRATION, STAGING, PRODUCTION;

        static Environment parse(String raw) {
            try {
                return Environment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ConfigException("BAOBAB_ENVIRONMENT must be one of development, integration, staging, production");
            }
        }
    }

    public static final String TEMPORARY_PROVIDER = "temporary";

    public static Config fromEnvironment(Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        Environment environment = null;
        String rawEnvironment = env.get("BAOBAB_ENVIRONMENT");
        if (rawEnvironment == null || rawEnvironment.isBlank()) {
            problems.add("BAOBAB_ENVIRONMENT is required");
        } else {
            try {
                environment = Environment.parse(rawEnvironment);
            } catch (ConfigException e) {
                problems.add(e.getMessage());
            }
        }
        int port = parseInt(env.getOrDefault("HTTP_PORT", "8080"), "HTTP_PORT", 0, 65535, problems);
        int grace = parseInt(env.getOrDefault("SHUTDOWN_GRACE_SECONDS", "10"), "SHUTDOWN_GRACE_SECONDS", 0, 120, problems);

        String databaseUrl = blankToNull(env.get("DATABASE_URL"));
        if (databaseUrl != null && !databaseUrl.startsWith("jdbc:postgresql://")) {
            problems.add("DATABASE_URL must be a jdbc:postgresql:// URL");
        }
        if (databaseUrl == null && environment != null
                && (environment == Environment.STAGING || environment == Environment.PRODUCTION)) {
            problems.add("DATABASE_URL is required in " + environment.name().toLowerCase(Locale.ROOT)
                    + ": in-memory state is for development and integration only");
        }

        String provider = env.getOrDefault("BILLING_PROVIDER", TEMPORARY_PROVIDER).trim().toLowerCase(Locale.ROOT);
        if (!provider.equals(TEMPORARY_PROVIDER)) {
            problems.add("BILLING_PROVIDER must be temporary: the Kill Bill provider is not implemented yet");
        } else if (environment == Environment.PRODUCTION) {
            problems.add("the temporary billing provider is simulated and is refused in production");
        }

        URI issuer = parseUri(env.get("WORKLOAD_ISSUER"), "WORKLOAD_ISSUER", problems);
        URI jwks = parseUri(env.get("WORKLOAD_JWKS_URI"), "WORKLOAD_JWKS_URI", problems);
        if (jwks != null && environment != null && environment != Environment.DEVELOPMENT
                && !"https".equals(jwks.getScheme())) {
            problems.add("WORKLOAD_JWKS_URI must use https outside development");
        }
        String audience = env.getOrDefault("WORKLOAD_AUDIENCE", "baobab-subscriptions").trim();
        if (audience.isEmpty()) {
            problems.add("WORKLOAD_AUDIENCE must not be empty");
        }
        Set<String> clients = new TreeSet<>();
        for (String client : env.getOrDefault("WORKLOAD_ALLOWED_CLIENTS", "baobab-cp-workload").split(",")) {
            if (!client.isBlank()) {
                clients.add(client.trim());
            }
        }
        if (clients.isEmpty()) {
            problems.add("WORKLOAD_ALLOWED_CLIENTS must name at least one client");
        }
        if (!problems.isEmpty()) {
            throw new ConfigException("invalid configuration: " + String.join("; ", problems));
        }
        return new Config(environment, port, Duration.ofSeconds(grace), databaseUrl,
                blankToNull(env.get("DATABASE_USER")), blankToNull(env.get("DATABASE_PASSWORD")), provider,
                issuer, jwks, audience, Set.copyOf(clients));
    }

    private static int parseInt(String raw, String name, int min, int max, List<String> problems) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                problems.add(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(name + " must be an integer");
            return min;
        }
    }

    private static URI parseUri(String raw, String name, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add(name + " is required");
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                problems.add(name + " must be an absolute URL");
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            problems.add(name + " must be a URL");
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        // Never print the database password.
        return "Config[environment=" + environment + ", httpPort=" + httpPort + ", database="
                + (databaseUrl == null ? "in-memory" : "postgresql") + ", billingProvider=" + billingProvider
                + ", workloadIssuer=" + workloadIssuer + ", workloadAudience=" + workloadAudience
                + ", allowedClients=" + allowedClients + "]";
    }
}
