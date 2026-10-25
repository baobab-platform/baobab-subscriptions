package com.baobabplatform.subscriptions.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.service.CallContext;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OPT-IN read-only acceptance against independently provisioned Kill Bill
 * (target 0.24.22 + PostgreSQL 17). It performs no mutation. The test is
 * skipped until a disposable real-provider fixture is actually available.
 */
class KillBillLiveCompatibilityTest {
    @Test
    void realKillBillExternalKeyReadBackMustMatchTenantAndSubscription() throws Exception {
        Map<String, String> env = System.getenv();
        String url = env.get("KILLBILL_IT_BASE_URL");
        assumeTrue(url != null && !url.isBlank(), "live Kill Bill integration fixture not configured");
        String tenant = required(env, "KILLBILL_IT_CP_TENANT");
        String subscription = required(env, "KILLBILL_IT_BSUB_ID");
        String expectedNativeId = required(env, "KILLBILL_IT_NATIVE_SUBSCRIPTION_ID");
        var providerTenant = new KillBillAdapter.TenantEndpoint(URI.create(url),
                Path.of(required(env, "KILLBILL_IT_USERNAME_FILE")),
                Path.of(required(env, "KILLBILL_IT_PASSWORD_FILE")),
                Path.of(required(env, "KILLBILL_IT_APIKEY_FILE")),
                Path.of(required(env, "KILLBILL_IT_APISECRET_FILE")));
        try (KillBillAdapter provider = new KillBillAdapter(Map.of(tenant, providerTenant));
             InMemoryBillingStore store = new InMemoryBillingStore()) {
            BillingService service = new BillingService(store, provider, PaymentsPort.notConfigured(),
                    BillingPolicies.load(), Fixtures.CLOCK, (t, id, reference) -> true);
            // A synthetic projection supplies only the local policy structure.
            // Its ID is replaced with the independently provisioned BSub fixture.
            String localTenant = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
            String payload = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                    .replace(Fixtures.ACME_TENANT, localTenant);
            String localId = service.ensure(new CallContext("baobab-cp-workload", "synthetic-actor",
                    "provider-live-" + UUID.randomUUID(), UUID.randomUUID().toString()),
                    payload.getBytes(StandardCharsets.UTF_8))
                    .body().path("billing_subscription_id").asText();
            Projection original = service.get(localTenant, localId);
            Projection current = new Projection(subscription, tenant, original.productSubscriptionId(),
                    original.authoritativeRevision(), original.productId(), original.platformAccountId(),
                    original.legalEntityId(), original.subscriptionType(), original.classification(),
                    original.billingPolicy(), original.billingState(), original.operationalCondition(),
                    original.readiness(), original.provider(), original.version(),
                    original.createdAt(), original.updatedAt());
            var intent = new ProviderOperationIntent(UUID.randomUUID(), tenant, subscription,
                    current.authoritativeRevision(), "ENSURE_SUBSCRIPTION", "a".repeat(64));
            ProviderOutcome result = provider.observeSubscription(current, intent);
            assertEquals(ProviderOutcome.Status.ALREADY_CONVERGED, result.status());
            assertEquals(expectedNativeId, result.providerReference(),
                    "read-back must be one previously provisioned provider subscription");
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required live integration fixture absent: " + name);
        }
        return value;
    }
}
