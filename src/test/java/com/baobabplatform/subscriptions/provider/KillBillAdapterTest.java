package com.baobabplatform.subscriptions.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.service.CallContext;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kill Bill 0.24.x REST wire compatibility without a payment plugin or
 * external network. This is NOT a real Kill Bill/PostgreSQL qualification.
 */
class KillBillAdapterTest {
    @TempDir Path tmp;

    private Path secret(String name, String value) throws Exception {
        Path path = tmp.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    @Test
    void explicitTenantBindingsRequireSafeOrigin() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new KillBillAdapter.TenantEndpoint(
                java.net.URI.create("http://billing.example.org:8080"),
                tmp, tmp, tmp, tmp));
        assertThrows(IllegalArgumentException.class, () -> new KillBillAdapter.TenantEndpoint(
                java.net.URI.create("https://billing.example.org/admin"),
                tmp, tmp, tmp, tmp));
        assertThrows(IllegalArgumentException.class, () -> new KillBillAdapter(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> KillBillAdapter.externalKey("foreign-subscription"));
    }

    @Test
    void observationAndApprovedSinglePostUseStableExternalKeyAndNeverGuessMoney() throws Exception {
        String tenant = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
        String nativeId = UUID.randomUUID().toString();
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> nativeKey = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1.0/kb/subscriptions", ex -> {
            byte[] result;
            int status;
            if (!"Basic".equals(ex.getRequestHeaders().getFirst("Authorization").split(" ")[0])
                    || !"synthetic-key".equals(ex.getRequestHeaders().getFirst("X-Killbill-ApiKey"))) {
                status = 403;
                result = new byte[0];
            } else if ("GET".equals(ex.getRequestMethod())) {
                observations.incrementAndGet();
                status = nativeKey.get() == null ? 404 : 200;
                result = nativeKey.get() == null ? new byte[0] :
                    ("{\"subscriptionId\":\"" + nativeId + "\",\"externalKey\":\""
                            + nativeKey.get() + "\",\"state\":\"ACTIVE\"}").getBytes(StandardCharsets.UTF_8);
            } else if ("POST".equals(ex.getRequestMethod())) {
                creates.incrementAndGet();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String external = KillBillAdapter.externalKey("bsub_unused");
                assertTrue(body.contains("\"planName\":\"synthetic-approved-monthly\""));
                assertTrue(body.contains("\"externalKey\""));
                nativeKey.set(com.baobabplatform.subscriptions.json.Json.mapper().readTree(body)
                        .path("externalKey").asText());
                assertFalse(external.equals(nativeKey.get()));
                status = 201;
                result = new byte[0]; // Kill Bill create can return only a bundle Location
            } else {
                status = 405;
                result = new byte[0];
            }
            ex.sendResponseHeaders(status, result.length == 0 ? -1 : result.length);
            if (result.length > 0) ex.getResponseBody().write(result);
            ex.close();
        });
        server.start();
        try {
            var cfg = new KillBillAdapter.TenantEndpoint(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    secret("username", "synthetic-user"), secret("password", "synthetic-pass"),
                    secret("api-key", "synthetic-key"), secret("api-secret", "synthetic-secret"));
            try (KillBillAdapter adapter = new KillBillAdapter(Map.of(tenant, cfg));
                 InMemoryBillingStore store = new InMemoryBillingStore()) {
                BillingService billing = new BillingService(store, adapter, PaymentsPort.notConfigured(),
                        BillingPolicies.load(), Fixtures.CLOCK, (t, sub, r) -> true);
                String body = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                        .replace(Fixtures.ACME_TENANT, tenant);
                CallContext ctx = new CallContext("baobab-cp-workload", "synthetic-actor",
                        "test-key-" + UUID.randomUUID(), UUID.randomUUID().toString());
                var result = billing.ensure(ctx, body.getBytes(StandardCharsets.UTF_8)).body();
                String bsId = result.path("billing_subscription_id").asText();
                Projection projection = billing.get(tenant, bsId);
                ProviderOperation op = store.transact(tx -> tx.providerOperationForKey(
                        tenant, "KILLBILL", "ENSURE_SUBSCRIPTION", ctx.idempotencyKey())).orElseThrow();
                ProviderOperationIntent intent = new ProviderOperationIntent(op.operationId(),
                        tenant, bsId, op.authoritativeRevision(), op.operationKind(), op.requestSha256());
                assertThrows(UnsupportedOperationException.class, () -> adapter.ensureSubscription(projection));
                assertEquals(ProviderOutcome.Status.UNKNOWN_OUTCOME, adapter.observeSubscription(projection, intent).status());
                var wrong = new KillBillAdapter.AuthorisedTerms(tenant, bsId, op.authoritativeRevision()+1,
                        UUID.randomUUID(), "synthetic-approved-monthly", UUID.randomUUID());
                assertEquals(ProviderOutcome.Status.PERMANENT_FAILURE,
                        adapter.ensureAuthorised(projection, intent, wrong).status());
                assertEquals(0, creates.get());
                var terms = new KillBillAdapter.AuthorisedTerms(tenant, bsId, op.authoritativeRevision(),
                        UUID.randomUUID(), "synthetic-approved-monthly", UUID.randomUUID());
                assertEquals(ProviderOutcome.Status.ALREADY_CONVERGED,
                        adapter.ensureAuthorised(projection, intent, terms).status());
                assertEquals(1, creates.get());
                assertTrue(observations.get() >= 3);
                assertEquals(nativeId, adapter.observeSubscription(projection, intent).providerReference());
                // Kill Bill's external key makes another ensure converge by read-back.
                assertTrue(adapter.ensureAuthorised(projection, intent, terms).isConverged());
                assertEquals(1, creates.get());
                assertEquals("KILLBILL", adapter.kind());
                assertFalse(adapter.capabilities().usage());
            }
        } finally {
            server.stop(0);
        }
    }
}
