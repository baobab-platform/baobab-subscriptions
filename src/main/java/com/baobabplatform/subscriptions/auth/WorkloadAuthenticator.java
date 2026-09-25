package com.baobabplatform.subscriptions.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies Baobab workload identity (ADR-SUB-0001 section 8): a signed JWT from
 * the configured issuer, for this engine's audience, with actor_type workload,
 * from an allowed client, living at most 15 minutes. Static bearer secrets are
 * not accepted.
 */
public final class WorkloadAuthenticator {
    private static final long MAX_LIFETIME_SECONDS = 900;

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final Set<String> allowedClients;

    /** The authenticated workload. */
    public record Caller(String subject, String clientId, Set<String> scopes, String tokenId) {
        public boolean hasScope(String scope) {
            return scopes.contains(scope);
        }
    }

    public WorkloadAuthenticator(URI issuer, JWKSource<SecurityContext> keys, String audience, Set<String> allowedClients) {
        this.allowedClients = Set.copyOf(allowedClients);
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(
                Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256), keys));
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience,
                new JWTClaimsSet.Builder().issuer(issuer.toString()).build(),
                Set.of("sub", "exp", "iat", "jti", "scope", "actor_type")));
        this.processor = p;
    }

    /** Keys fetched (and cached, with rotation) from the issuer's JWKS endpoint. */
    public static WorkloadAuthenticator remote(URI issuer, URI jwks, String audience, Set<String> allowedClients) {
        try {
            JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(jwks.toURL()).retrying(true).build();
            return new WorkloadAuthenticator(issuer, keys, audience, allowedClients);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("WORKLOAD_JWKS_URI is not a URL", e);
        }
    }

    /** The caller behind an Authorization header, holding requiredScope. */
    public Caller authenticate(String authorization, String requiredScope) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() <= 7) {
            throw new AuthException(401, "AUTH_TOKEN_REQUIRED", "a bearer token is required");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(authorization.substring(7).trim(), null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new AuthException(401, "AUTH_TOKEN_INVALID", "the bearer token is invalid");
        }
        Date issued = claims.getIssueTime();
        Date expires = claims.getExpirationTime();
        if (issued == null || expires == null || (expires.getTime() - issued.getTime()) / 1000 > MAX_LIFETIME_SECONDS) {
            throw new AuthException(401, "AUTH_TOKEN_INVALID", "the bearer token is invalid");
        }
        String actorType = stringClaim(claims, "actor_type");
        String client = stringClaim(claims, "azp");
        if (client == null) {
            client = stringClaim(claims, "client_id");
        }
        String scope = stringClaim(claims, "scope");
        Set<String> scopes = scope == null ? Set.of()
                : Arrays.stream(scope.split(" ")).filter(s -> !s.isBlank()).collect(Collectors.toCollection(HashSet::new));
        if (!"workload".equals(actorType) || client == null || !allowedClients.contains(client) || !scopes.contains(requiredScope)) {
            throw new AuthException(403, "AUTHORIZATION_DENIED", "the authenticated principal lacks required authority");
        }
        return new Caller(claims.getSubject(), client, Set.copyOf(scopes), claims.getJWTID());
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (ParseException e) {
            return null;
        }
    }
}
