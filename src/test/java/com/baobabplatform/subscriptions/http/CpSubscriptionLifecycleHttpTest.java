package com.baobabplatform.subscriptions.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baobabplatform.subscriptions.Fixtures;
import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.policy.BillingPolicies;
import com.baobabplatform.subscriptions.provider.TemporaryProvider;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.store.CpSubscriptionLifecycleInbox;
import com.baobabplatform.subscriptions.store.InMemoryBillingStore;
import com.baobabplatform.subscriptions.store.CpSubscriptionLifecycleInboxTest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Signed issuer/audience/client/scope gates precede any CP event processing. */
class CpSubscriptionLifecycleHttpTest {
    private static final URI ISSUER = URI.create("https://iam.baobab.test/realms/baobab");

    private static String token(RSAKey key, String client, String audience, String scopes) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER.toString())
                .subject("service-account-cp").audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString()).claim("actor_type", "workload")
                .claim("azp", client).claim("scope", scopes).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("k1").type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void exactSignedCpWorkloadAndScopeRequired() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        WorkloadAuthenticator auth = new WorkloadAuthenticator(ISSUER,
                new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                "baobab-subscriptions", Set.of("baobab-cp-workload", "other-allowlisted"));
        AtomicInteger saved = new AtomicInteger();
        CpSubscriptionLifecycleInbox observer = new CpSubscriptionLifecycleInbox() {
            @Override
            public Receipt receiveSubscriptionEvent(byte[] body, String clientId) {
                Notification parsed = CpSubscriptionLifecycleInbox.verify(body);
                saved.incrementAndGet();
                return new Receipt(parsed.eventId(), false, "PENDING_CP_REFRESH");
            }

            @Override
            public List<Notification> pending(String tenant, int limit) {
                return List.of();
            }
        };
        InMemoryBillingStore store = new InMemoryBillingStore();
        TemporaryProvider provider = new TemporaryProvider();
        BillingService service = new BillingService(store, provider, new Fixtures.RecordingPayments(),
                BillingPolicies.load(), Fixtures.CLOCK, (tenant, id, ref) -> true);
        try (HttpApi api = new HttpApi(0, service, auth, store, provider, "integration",
                null, null, null, observer, "baobab-cp-workload");
             HttpClient http = HttpClient.newHttpClient()) {
            api.start();
            URI endpoint = URI.create("http://127.0.0.1:" + api.port()
                    + "/internal/v1/cp-subscription-lifecycle-events");
            byte[] body = CpSubscriptionLifecycleInboxTest.event(UUID.randomUUID(), "created");
            assertEquals(401, send(http, endpoint, null, body));
            assertEquals(401, send(http, endpoint, token(key, "baobab-cp-workload",
                    "other-engine", "billing:observe"), body));
            assertEquals(403, send(http, endpoint, token(key, "baobab-cp-workload",
                    "baobab-subscriptions", "billing:read"), body));
            assertEquals(403, send(http, endpoint, token(key, "other-allowlisted",
                    "baobab-subscriptions", "billing:observe"), body));
            assertEquals(0, saved.get());
            assertEquals(202, send(http, endpoint, token(key, "baobab-cp-workload",
                    "baobab-subscriptions", "billing:observe"), body));
            assertEquals(1, saved.get());
        }
    }

    private static int send(HttpClient http, URI url, String bearer, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/cloudevents+json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}
