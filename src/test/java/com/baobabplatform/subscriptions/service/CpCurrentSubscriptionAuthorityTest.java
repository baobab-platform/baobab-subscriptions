package com.baobabplatform.subscriptions.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.domain.Projection;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.payments.PaymentsPort;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.provider.ProviderCapabilities;
import com.baobabplatform.subscriptions.provider.ProviderOperationIntent;
import com.baobabplatform.subscriptions.provider.ProviderOutcome;
import com.baobabplatform.subscriptions.domain.UsageRecord;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.baobabplatform.subscriptions.store.ProviderOperation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CP-bound read can unlock observation, NEVER commercial dispatch in v1. */
class CpCurrentSubscriptionAuthorityTest {
    @TempDir Path tmp;
    private static final class FakeRealProvider implements BillingProvider {
        @Override public String kind() { return "KILL_BILL"; }
        @Override public boolean simulated() { return false; }
        @Override public String ensureSubscription(Projection p) { throw new AssertionError("no provider mutation"); }
        @Override public void suspend(Projection p) { throw new AssertionError("no provider mutation"); }
        @Override public void cancel(Projection p) { throw new AssertionError("no provider mutation"); }
        @Override public void recordUsage(Projection p, UsageRecord u) { throw new AssertionError("no provider mutation"); }
        @Override public boolean healthy() { return true; }
        @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.none(); }
    }

    @Test
    void currentLeaseFencesIdentityRevisionAndClassificationWhilePreflightStaysDenyOnly() throws Exception {
        try (InMemoryBillingStore store = new InMemoryBillingStore()) {
            var provider = new FakeRealProvider();
            BillingService service = new BillingService(store, provider, PaymentsPort.notConfigured(),
                    BillingPolicies.load(), Clock.systemUTC(), (t,s,r) -> true);
            String tenant = Fixtures.ACME_TENANT + UUID.randomUUID().toString().replace("-", "");
            String payload = new String(Fixtures.acmeCommercial(), StandardCharsets.UTF_8)
                    .replace(Fixtures.ACME_TENANT, tenant);
            String key = "cp-current-" + UUID.randomUUID();
            var ensured = service.ensure(new CallContext("baobab-cp-workload","synthetic-operator",
                    key, UUID.randomUUID().toString()),payload.getBytes(StandardCharsets.UTF_8)).body();
            Projection p = service.get(tenant, ensured.path("billing_subscription_id").asText());
            ProviderOperation op = store.transact(tx -> tx.providerOperationForKey(
                    tenant,"KILL_BILL","ENSURE_SUBSCRIPTION",key)).orElseThrow();
            AtomicInteger preflight = new AtomicInteger();
            AtomicInteger badResponse = new AtomicInteger();
            AtomicInteger seen = new AtomicInteger();
            HttpServer cp = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            cp.createContext("/internal/subscriptions/v1", request -> {
                seen.incrementAndGet();
                int status = 200;
                byte[] raw;
                Instant now = Instant.now();
                if (!"Bearer test-cp-workload-token".equals(request.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                    raw = "{}".getBytes(StandardCharsets.UTF_8);
                } else if (request.getRequestURI().getPath().endsWith("/commercial-authority")) {
                    preflight.incrementAndGet();
                    var received=Json.mapper().readTree(request.getRequestBody());
                    var response=Json.mapper().createObjectNode()
                            .put("tenant_id",tenant).put("product_subscription_id",p.productSubscriptionId())
                            .put("authoritative_revision",op.authoritativeRevision())
                            .put("operation_id",op.operationId().toString())
                            .put("outcome","DENIED").put("reason_code","COMMERCIAL_TERMS_NOT_ATTESTED")
                            .put("evaluated_at",now.toString()).put("expires_at",now.plusSeconds(3).toString())
                            .put("policy_reference",
                                "ADR-SHARED-011/ADR-BCP-017/ADR-SUB-0015/financial-preflight:v1");
                    assertEquals(op.operationId().toString(), received.path("operation_id").asText());
                    raw=Json.mapper().writeValueAsBytes(response);
                } else {
                    var response=Json.mapper().createObjectNode()
                            .put("tenant_id",tenant)
                            .put("product_subscription_id",p.productSubscriptionId())
                            .put("product_id",p.productId()).put("status","ACTIVE")
                            .put("authoritative_revision",p.authoritativeRevision())
                            .put("primary_organisation_id","org_test")
                            .put("evaluated_at",now.toString()).put("expires_at",now.plusSeconds(3).toString())
                            .put("policy_reference","ADR-SHARED-011/ADR-BCP-017/ADR-SUB-0003/current:v1");
                    var classification=response.putObject("classification")
                            .put("classification_id",p.classification().classificationId())
                            .put("subscription_type","COMMERCIAL")
                            .put("classification_source",p.classification().classificationSource())
                            .put("classification_reference",p.classification().classificationReference());
                    if(badResponse.get()==1) response.put("authoritative_revision",p.authoritativeRevision()+1);
                    if(badResponse.get()==2) classification.put("subscription_type","INTERNAL");
                    if(badResponse.get()==3) response.put("tenant_id","tn_foreign");
                    if(badResponse.get()==4) response.put("expires_at",now.minusSeconds(1).toString());
                    if(badResponse.get()==5) classification.put("classification_source","RECLASSIFICATION");
                    raw=Json.mapper().writeValueAsBytes(response);
                }
                request.sendResponseHeaders(status,raw.length);
                try(var os=request.getResponseBody()){os.write(raw);}
            });
            cp.start();
            try {
                Path token = tmp.resolve("iam-token");
                Files.writeString(token,"test-cp-workload-token");
                var authority = new CpCurrentSubscriptionAuthority(store,
                        URI.create("http://127.0.0.1:"+cp.getAddress().getPort()),token,
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                            .followRedirects(HttpClient.Redirect.NEVER).build(),Clock.systemUTC());
                assertTrue(authority.permitsObservation(op));
                assertTrue(authority.approveDispatch(op).isEmpty(), "Shared v1 never authorizes money");
                assertEquals(1, preflight.get());
                for (int i=1;i<=5;i++){
                    badResponse.set(i);
                    assertFalse(authority.permitsObservation(op),"CP drift or stale lease must deny");
                }
                badResponse.set(0);
                Files.writeString(token,"wrong-token");
                assertFalse(authority.permitsObservation(op),"issuer denial must not fallback to cached CP response");
                assertTrue(seen.get()>=7);
            } finally { cp.stop(0); }
        }
    }
}
