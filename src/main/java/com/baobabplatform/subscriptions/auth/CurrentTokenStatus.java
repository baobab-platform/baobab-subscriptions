package com.baobabplatform.subscriptions.auth;

import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.service.BoundedJsonBodySubscriber;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Current issuer status augments signature verification; never caches positives. */
@FunctionalInterface
public interface CurrentTokenStatus {
    boolean active(String token, String subject, String client, String audience, Set<String> scopes);

    static CurrentTokenStatus remote(URI endpoint, String clientId, Path secretFile) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !secretFile.isAbsolute()
                || clientId == null || !clientId.matches("[A-Za-z0-9._-]{3,128}")) {
            throw new IllegalArgumentException("explicit TLS issuer status and credential file required");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (token, subject, client, audience, scopes) -> {
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                byte[] bytes;
                try (var in = Files.newInputStream(secretFile)) { bytes = in.readNBytes(8193); }
                if (bytes.length == 0 || bytes.length > 8192) return false;
                String secret = StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim();
                if (secret.isEmpty() || secret.chars().anyMatch(c -> c <= 32 || c >= 127)) return false;
                String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret)
                        .getBytes(StandardCharsets.UTF_8));
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("token="
                                + URLEncoder.encode(token, StandardCharsets.UTF_8))).build();
                pending = http.sendAsync(request, ignored -> new BoundedJsonBodySubscriber());
                var response = pending.get(3, TimeUnit.SECONDS);
                if (response.statusCode() != 200) return false;
                var result = Json.mapper().readTree(response.body());
                if (result == null || !result.path("active").isBoolean() || !result.path("active").booleanValue()
                        || !subject.equals(result.path("sub").asText())
                        || !client.equals(result.path("client_id").asText())) return false;
                var aud = result.path("aud");
                boolean matches = audience.equals(aud.asText());
                if (aud.isArray()) for (var item : aud) matches |= audience.equals(item.asText());
                Set<String> current = Arrays.stream(result.path("scope").asText().split(" "))
                        .filter(s -> !s.isBlank()).collect(Collectors.toSet());
                return matches && current.containsAll(scopes);
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                return false;
            } finally {
                if (pending != null && !pending.isDone()) pending.cancel(true);
            }
        };
    }
}
