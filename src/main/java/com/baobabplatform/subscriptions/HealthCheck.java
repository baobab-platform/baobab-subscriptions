package com.baobabplatform.subscriptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The container HEALTHCHECK: exits 0 when /health/live answers 200. Needs no shell or curl in the image. */
public final class HealthCheck {
    private HealthCheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("HTTP_PORT", "8080");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
