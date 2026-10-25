package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.provider.KillBillAdapter;
import com.baobabplatform.subscriptions.provider.ProviderOperationIntent;
import com.baobabplatform.subscriptions.provider.ProviderOutcome;
import com.baobabplatform.subscriptions.store.PostgresBillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import com.baobabplatform.subscriptions.log.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Controlled bounded provider worker; read-only reconciliation by default.
 *
 * <p>Every tick verifies a currently valid IAM workload token. Provider
 * mutation also needs a fresh CP/terms decision; no transport event or local
 * projection can supply that decision. The default port denies every action.
 * If the CP workload current-state route is not qualified, no dispatch
 * ever occurs, regardless of provider connectivity.
 */
public final class ProviderWorkScheduler implements AutoCloseable {
    public interface CpAuthority {
        /** Independent current CP check for even observational workload IO. */
        boolean permitsObservation(ProviderOperation operation);

        /** CP + payer + plan provenance, or no permission. */
        Optional<KillBillAdapter.AuthorisedTerms> approveDispatch(ProviderOperation operation);

        static CpAuthority unavailable() {
            return new CpAuthority() {
                @Override public boolean permitsObservation(ProviderOperation op) { return false; }
                @Override public Optional<KillBillAdapter.AuthorisedTerms> approveDispatch(ProviderOperation op) {
                    return Optional.empty();
                }
            };
        }
    }

    public record Tick(int examined, int reconciled, int blocked, int failed) {
    }

    private final PostgresBillingStore store;
    private final ProviderReconciliationService reconciliation;
    private final KillBillAdapter provider;
    private final WorkloadAuthenticator authenticator;
    private final Path tokenFile;
    private final String expectedClient;
    private final Set<String> tenants;
    private final CpAuthority cpAuthority;
    private final Clock clock;
    private final boolean dispatchEnabled;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("billing-provider-observer").daemon(true).unstarted(r));

    public ProviderWorkScheduler(PostgresBillingStore store, ProviderReconciliationService reconciliation,
                                 KillBillAdapter provider, WorkloadAuthenticator authenticator,
                                 Path tokenFile, String expectedClient, Set<String> tenants,
                                 CpAuthority cpAuthority, Clock clock, boolean dispatchEnabled) {
        this.store = Objects.requireNonNull(store);
        this.reconciliation = Objects.requireNonNull(reconciliation);
        this.provider = Objects.requireNonNull(provider);
        this.authenticator = Objects.requireNonNull(authenticator);
        this.tokenFile = Objects.requireNonNull(tokenFile);
        this.expectedClient = Objects.requireNonNull(expectedClient);
        this.tenants = Set.copyOf(tenants);
        this.cpAuthority = Objects.requireNonNull(cpAuthority);
        this.clock = Objects.requireNonNull(clock);
        this.dispatchEnabled = dispatchEnabled;
        if (expectedClient.isBlank() || tenants.isEmpty()
                || tenants.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new IllegalArgumentException("exact IAM worker ID and tenant allowlist required");
        }
    }

    /** Schedules with a bounded interval, no recursive or unbounded retry loops. */
    public void start(Duration interval) {
        if (interval == null || interval.compareTo(Duration.ofSeconds(30)) < 0
                || interval.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("worker interval must be 30 seconds to 30 minutes");
        }
        loop.scheduleWithFixedDelay(this::safeTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            Tick tick = runOnce();
            Log.info("provider.worker.tick", Map.of("examined", tick.examined(),
                    "reconciled", tick.reconciled(), "blocked", tick.blocked(),
                    "failed", tick.failed()));
        } catch (RuntimeException error) {
            // No provider or credential contents in logs.
            Log.error("provider.worker.refused", Map.of("reason", error.getClass().getSimpleName()));
        }
    }

    private WorkloadAuthenticator.Caller authenticate(String scope) {
        try {
            String bearer = Files.readString(tokenFile).trim();
            WorkloadAuthenticator.Caller caller = authenticator.authenticate("Bearer " + bearer, scope);
            if (!expectedClient.equals(caller.clientId())) {
                throw new IllegalStateException("unapproved provider worker identity");
            }
            return caller;
        } catch (IOException unavailable) {
            throw new IllegalStateException("IAM workload token unavailable", unavailable);
        }
    }

    /** One bounded pass. No network IO occurs inside a PostgreSQL transaction. */
    public Tick runOnce() {
        WorkloadAuthenticator.Caller observer = authenticate("billing:observe");
        int examined = 0, reconciled = 0, blocked = 0, failed = 0;
        for (String tenant : tenants) {
            List<ProviderOperation> due = store.dueProviderObservations(tenant, 25, clock.instant());
            for (ProviderOperation operation : due) {
                examined++;
                if (!cpAuthority.permitsObservation(operation)) {
                    blocked++;
                    // Persist cooldown for a CP authority outage as well;
                    // never repeatedly poll a foreign or revoked tenant.
                    store.recordReconciliationAttempt(tenant, operation.operationId(),
                            false, "CP_OBSERVATION_AUTHORITY_UNAVAILABLE", clock.instant());
                    continue;
                }
                try {
                    ProviderOperation outcome = reconciliation.reconcile(tenant, operation.operationId(),
                            observer.clientId(), Duration.ofSeconds(45));
                    boolean resolved = outcome.state() == ProviderOperation.State.RECONCILED;
                    store.recordReconciliationAttempt(tenant, operation.operationId(), resolved,
                            outcome.reasonCode(), clock.instant());
                    if (resolved) reconciled++;
                    else failed++;
                } catch (RuntimeException unavailable) {
                    store.recordReconciliationAttempt(tenant, operation.operationId(), false,
                            "OBSERVATION_WORKER_FAILURE", clock.instant());
                    failed++;
                }
            }

            // Execution is opt-in on the constructor and subject to current
            // CP decision, JWT billing:manage and tenant-specific binding.
            // Unqualified CP endpoints cannot be guessed from subscriptions.
            if (dispatchEnabled) {
                List<ProviderOperation> pending = store.preparedProviderOperations(tenant, 10);
                for (ProviderOperation op : pending) {
                    examined++;
                    Optional<KillBillAdapter.AuthorisedTerms> permission = cpAuthority.approveDispatch(op);
                    if (permission.isEmpty()) {
                        blocked++;
                        continue;
                    }
                    KillBillAdapter.AuthorisedTerms terms = permission.get();
                    if (!tenant.equals(terms.tenantId())
                            || !op.billingSubscriptionId().equals(terms.billingSubscriptionId())
                            || op.authoritativeRevision() != terms.revision()
                            || !"ENSURE_SUBSCRIPTION".equals(op.operationKind())) {
                        blocked++;
                        continue;
                    }
                    WorkloadAuthenticator.Caller executor = authenticate("billing:manage");
                    // Prepare/fence journal FIRST; no retry on a partially
                    // completed call or after an expired dispatch lease.
                    try {
                        ProviderOperation claimed = reconciliation.claimForDispatch(
                                tenant, op.operationId(), executor.clientId(), Duration.ofSeconds(45));
                        Projection projection = store.transact(tx ->
                                tx.projection(tenant, claimed.billingSubscriptionId()).orElseThrow());
                        ProviderOperationIntent intent = new ProviderOperationIntent(claimed.operationId(),
                                tenant, claimed.billingSubscriptionId(), claimed.authoritativeRevision(),
                                claimed.operationKind(), claimed.requestSha256());
                        ProviderOutcome outcome = provider.ensureAuthorised(projection, intent, terms);
                        reconciliation.recordDispatchOutcome(tenant, claimed.operationId(),
                                executor.clientId(), claimed.leaseEpoch(), outcome);
                    } catch (RuntimeException uncertain) {
                        // A claimed operation can have reached Kill Bill.
                        // Only the read-only expired-SENT recovery may proceed.
                        failed++;
                    }
                }
            }
        }
        return new Tick(examined, reconciled, blocked, failed);
    }

    /** Operator monitoring is tenant-scoped, never provider credentials. */
    public Map<String, Long> counts(String tenantId) {
        if (!tenants.contains(tenantId)) throw new IllegalArgumentException("tenant not on worker allowlist");
        authenticate("billing:observe");
        return store.reconciliationCounts(tenantId);
    }

    @Override public void close() { loop.shutdownNow(); }
}
