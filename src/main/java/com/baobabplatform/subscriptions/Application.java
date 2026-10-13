package com.baobabplatform.subscriptions;

import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.config.Config;
import com.baobabplatform.subscriptions.config.ConfigException;
import com.baobabplatform.subscriptions.contract.Contracts;
import com.baobabplatform.subscriptions.contract.ContractProbe;
import com.baobabplatform.subscriptions.http.HttpApi;
import com.baobabplatform.subscriptions.log.Log;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.service.CpInternalAuthorityGate;
import com.baobabplatform.subscriptions.service.InternalAuthorityGate;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.baobabplatform.subscriptions.store.FoundingEventInbox;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import java.time.Clock;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Starts the Baobab Billing API, and stops it gracefully on SIGTERM. */
public final class Application {
    private Application() {
    }

    public static void main(String[] args) throws Exception {
        // Offline packaging probes must not construct a provider or access
        // issuer credentials/database configuration.
        if (args.length > 0 && args[0].startsWith("contract-")) {
            ContractProbe.main(args);
            return;
        }
        Contracts.verifyBundle();
        Config config;
        try {
            config = Config.fromEnvironment(System.getenv());
        } catch (ConfigException e) {
            Log.error("startup.refused", Map.of("reason", e.getMessage()));
            System.exit(2);
            return;
        }
        BillingPolicies policies = BillingPolicies.load();
        BillingStore store = config.databaseUrl() == null ? new InMemoryBillingStore()
                : new PostgresBillingStore(config.databaseUrl(), config.databaseUser(), config.databasePassword());
        BillingProvider provider = new TemporaryProvider();
        // Only a controlled nonproduction rehearsal can opt in. A missing
        // workload credential or unavailable CP never restores historical
        // INTERNAL permission; the default always denies.
        InternalAuthorityGate internalAuthority = InternalAuthorityGate.unavailable();
        if ("true".equals(System.getenv("PEO_INTERNAL_AUTHORITY_ENABLED"))) {
            if (config.environment() == Config.Environment.PRODUCTION) {
                throw new ConfigException("PEO INTERNAL current-authority integration is not production-certified");
            }
            String endpoint = System.getenv("PEO_CP_INTERNAL_AUTHORITY_BASE_URL");
            String credentialFile = System.getenv("PEO_CP_INTERNAL_AUTHORITY_TOKEN_FILE");
            if (endpoint == null || endpoint.isBlank() || credentialFile == null || credentialFile.isBlank()) {
                throw new ConfigException("PEO INTERNAL authority requires a CP endpoint and an IAM-managed workload token file");
            }
            internalAuthority = CpInternalAuthorityGate.create(URI.create(endpoint), Path.of(credentialFile));
        }
        BillingService billing = new BillingService(store, provider, PaymentsPort.notConfigured(),
                policies, Clock.systemUTC(), internalAuthority);
        WorkloadAuthenticator auth = WorkloadAuthenticator.remote(config.workloadIssuer(), config.workloadJwksUri(),
                config.workloadAudience(), config.allowedClients());
        String environment = config.environment().name().toLowerCase(Locale.ROOT);
        FoundingEventInbox foundingInbox = null;
        String controlPlaneClient = null;
        if ("true".equals(System.getenv("PEO_FOUNDING_EVENT_RECEIVER_ENABLED"))) {
            if (config.environment() == Config.Environment.PRODUCTION
                    || !(store instanceof PostgresBillingStore)) {
                throw new ConfigException("PEO founding lifecycle receiver requires nonproduction PostgreSQL");
            }
            controlPlaneClient = System.getenv("PEO_CP_EVENT_CLIENT_ID");
            if (controlPlaneClient == null || controlPlaneClient.isBlank()) {
                throw new ConfigException("PEO founding event receiver requires verified CP workload client ID");
            }
            foundingInbox = (PostgresBillingStore) store;
        }
        // A billing:manage or usage:record bearer scope is not CP authority.
        // Do not guess a default principal from allowedClients; the exact
        // staging issuer/client binding must be supplied by IAM governance.
        String commandClient = System.getenv("BAOBAB_CP_BILLING_COMMAND_CLIENT_ID");
        if (config.environment() == Config.Environment.STAGING
                && (commandClient == null || commandClient.isBlank())) {
            throw new ConfigException("staging billing commands require the approved CP workload client ID");
        }
        // The approved CP producer must also be accepted by the JWT issuer
        // allowlist; otherwise a purported healthy staging deployment can
        // never receive any valid CP billing command.
        if (config.environment() == Config.Environment.STAGING
                && !config.allowedClients().contains(commandClient)) {
            throw new ConfigException("staging CP billing command client must be in WORKLOAD_ALLOWED_CLIENTS");
        }
        HttpApi api = new HttpApi(config.httpPort(), billing, auth, store, provider, environment,
                foundingInbox, controlPlaneClient, commandClient);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("shutdown.started", Map.of("grace_seconds", config.shutdownGrace().toSeconds()));
            api.stop(config.shutdownGrace());
            store.close();
            Log.info("shutdown.completed", Map.of());
            stopped.countDown();
        }, "shutdown"));
        api.start();
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("port", api.port());
        started.put("environment", environment);
        started.put("store", config.databaseUrl() == null ? "in-memory" : "postgresql");
        started.put("billing_provider", provider.kind());
        started.put("simulated", provider.simulated());
        Log.info("startup.completed", started);
        stopped.await();
    }
}
