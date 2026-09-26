package com.baobabplatform.subscriptions.http;

import com.baobabplatform.subscriptions.auth.AuthException;
import com.baobabplatform.subscriptions.auth.WorkloadAuthenticator;
import com.baobabplatform.subscriptions.json.Json;
import com.baobabplatform.subscriptions.log.Log;
import com.baobabplatform.subscriptions.provider.BillingProvider;
import com.baobabplatform.subscriptions.service.BillingException;
import com.baobabplatform.subscriptions.service.BillingService;
import com.baobabplatform.subscriptions.service.CallContext;
import com.baobabplatform.subscriptions.store.BillingStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Baobab Billing API over the JDK HTTP server. Every /v1 route requires a
 * workload token with the route's scope (Shared authorization/v1:
 * billing:manage, billing:read, usage:record); every mutation requires an
 * Idempotency-Key. Errors are RFC 9457 problems (Shared errors/v1). Logs carry
 * the route template, never tenant data or tokens.
 */
public final class HttpApi implements AutoCloseable {
    private static final int MAX_BODY = 64 * 1024;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String ID = "([A-Za-z0-9_]{3,63})";

    private final HttpServer server;
    private final ExecutorService executor;
    private final BillingService billing;
    private final WorkloadAuthenticator auth;
    private final BillingStore store;
    private final BillingProvider provider;
    private final String environment;
    private final List<Route> routes;

    private record Route(String method, String template, Pattern pattern, String scope, Handler handler) {
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(HttpExchange exchange, Matcher path, byte[] body, CallContext context);
    }

    private record Response(int status, Object body, boolean replayed) {
    }

    public HttpApi(int port, BillingService billing, WorkloadAuthenticator auth, BillingStore store, BillingProvider provider,
            String environment) throws IOException {
        this.billing = billing;
        this.auth = auth;
        this.store = store;
        this.provider = provider;
        this.environment = environment;
        this.routes = List.of(
                route("POST", "/v1/billing-projections", "billing:manage",
                        (ex, m, body, ctx) -> result(billing.ensure(ctx.withKey(idempotencyKey(ex)), body))),
                route("GET", "/v1/tenants/{tenant_id}/billing-projections/{billing_subscription_id}", "billing:read",
                        (ex, m, body, ctx) -> new Response(200, billing.get(m.group(1), m.group(2)), false)),
                route("GET", "/v1/tenants/{tenant_id}/product-subscriptions/{product_subscription_id}/billing-projection", "billing:read",
                        (ex, m, body, ctx) -> new Response(200, billing.getForProductSubscription(m.group(1), m.group(2)), false)),
                route("POST", "/v1/billing-projections/{billing_subscription_id}/suspend", "billing:manage",
                        (ex, m, body, ctx) -> result(billing.suspend(ctx.withKey(idempotencyKey(ex)), m.group(1), body))),
                route("POST", "/v1/billing-projections/{billing_subscription_id}/resume", "billing:manage",
                        (ex, m, body, ctx) -> result(billing.resume(ctx.withKey(idempotencyKey(ex)), m.group(1), body))),
                route("POST", "/v1/billing-projections/{billing_subscription_id}/terminate", "billing:manage",
                        (ex, m, body, ctx) -> result(billing.terminate(ctx.withKey(idempotencyKey(ex)), m.group(1), body))),
                route("POST", "/v1/billing-projections/{billing_subscription_id}/usage", "usage:record",
                        (ex, m, body, ctx) -> result(billing.recordUsage(ctx.withKey(idempotencyKey(ex)), m.group(1), body))));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::dispatch);
    }

    private static Route route(String method, String template, String scope, Handler handler) {
        String regex = "^" + template.replaceAll("\\{[a-z_]+}", ID) + "$";
        return new Route(method, template, Pattern.compile(regex), scope, handler);
    }

    private static Response result(BillingService.Result r) {
        return new Response(r.status(), r.body(), r.replayed());
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops accepting requests and waits up to grace for in-flight ones. */
    public void stop(Duration grace) {
        server.stop((int) Math.max(0, grace.toSeconds()));
        executor.close();
    }

    @Override
    public void close() {
        stop(Duration.ZERO);
    }

    private static String idempotencyKey(HttpExchange exchange) {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BillingException(400, "INVALID_IDEMPOTENCY_KEY",
                    "an Idempotency-Key of 16 to 128 characters [A-Za-z0-9._:-] is required", false);
        }
        return key;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String header = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
        String correlationId = header != null && UUID_PATTERN.matcher(header).matches() ? header.toLowerCase() : UUID.randomUUID().toString();
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        String template = "unmatched";
        String client = null;
        int status;
        try {
            if (method.equals("GET") && path.equals("/health/live")) {
                template = path;
                status = send(exchange, 200, Map.of("status", "UP"), correlationId, false, "application/json");
            } else if (method.equals("GET") && path.equals("/health/ready")) {
                template = path;
                status = ready(exchange, correlationId);
            } else {
                Route matched = null;
                Matcher matcher = null;
                boolean pathKnown = false;
                for (Route r : routes) {
                    Matcher m = r.pattern().matcher(path);
                    if (m.matches()) {
                        pathKnown = true;
                        if (r.method().equals(method)) {
                            matched = r;
                            matcher = m;
                            break;
                        }
                    }
                }
                if (matched == null) {
                    throw pathKnown ? new BillingException(405, "METHOD_NOT_ALLOWED", "method not allowed", false)
                            : new BillingException(404, "ROUTE_NOT_FOUND", "no such route", false);
                }
                template = matched.template();
                WorkloadAuthenticator.Caller caller = auth.authenticate(exchange.getRequestHeaders().getFirst("Authorization"), matched.scope());
                client = caller.clientId();
                byte[] body = method.equals("POST") ? readBody(exchange) : new byte[0];
                CallContext context = new CallContext(caller.clientId(), caller.subject(), null, correlationId);
                Response response = matched.handler().handle(exchange, matcher, body, context);
                status = send(exchange, response.status(), response.body(), correlationId, response.replayed(), "application/json");
            }
        } catch (AuthException e) {
            status = problem(exchange, e.status(), e.code(), e.getMessage(), false, List.of(), correlationId);
        } catch (BillingException e) {
            status = problem(exchange, e.status(), e.code(), e.getMessage(), e.retryable(), e.problems(), correlationId);
        } catch (RuntimeException e) {
            Log.error("request.failed", Map.of("route", template, "correlation_id", correlationId, "error", e.getClass().getSimpleName()));
            status = problem(exchange, 500, "BILLING_UNAVAILABLE", "the request could not be processed", true, List.of(), correlationId);
        } finally {
            exchange.close();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("route", template);
        fields.put("status", status);
        fields.put("duration_ms", (System.nanoTime() - started) / 1_000_000);
        fields.put("correlation_id", correlationId);
        if (client != null) {
            fields.put("client_id", client);
        }
        Log.info("http.request", fields);
    }

    private int ready(HttpExchange exchange, String correlationId) throws IOException {
        boolean storeUp = store.ping();
        boolean providerUp = provider.healthy();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", storeUp && providerUp ? "READY" : "NOT_READY");
        body.put("checks", Map.of("store", storeUp ? "UP" : "DOWN", "billing_provider", providerUp ? "UP" : "DOWN",
                "billing_policy", "LOADED"));
        // The engine can serve; that says nothing about money. Commercial billing
        // stays unavailable until a real provider and payment path exist.
        body.put("billing_provider", Map.of("kind", provider.kind(), "simulated", provider.simulated()));
        body.put("commercial_billing", provider.simulated() ? "NOT_CONFIGURED" : "CONFIGURED");
        body.put("environment", environment);
        return send(exchange, storeUp && providerUp ? 200 : 503, body, correlationId, false, "application/json");
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                throw new BillingException(413, "PAYLOAD_TOO_LARGE", "the request body is too large", false);
            }
            return body;
        }
    }

    private static int send(HttpExchange exchange, int status, Object body, String correlationId, boolean replayed, String contentType)
            throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Correlation-ID", correlationId);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (replayed) {
            exchange.getResponseHeaders().set("Idempotent-Replayed", "true");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }

    private static int problem(HttpExchange exchange, int status, String code, String detail, boolean retryable, List<String> problems,
            String correlationId) throws IOException {
        ObjectNode body = Json.mapper().createObjectNode()
                .put("type", "urn:baobab-platform:problem:" + code.toLowerCase().replace('_', '-'))
                .put("title", title(status))
                .put("status", status)
                .put("detail", detail.length() > 2048 ? detail.substring(0, 2048) : detail)
                .put("code", code)
                .put("correlation_id", correlationId)
                .put("retryable", retryable);
        if (!problems.isEmpty()) {
            ArrayNode errors = body.putArray("errors");
            for (String p : problems.subList(0, Math.min(problems.size(), 50))) {
                errors.addObject().put("code", "SCHEMA_VIOLATION").put("message", p.length() > 500 ? p.substring(0, 500) : p);
            }
        }
        return send(exchange, status, body, correlationId, false, "application/problem+json");
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 503 -> "Service Unavailable";
            default -> status >= 500 ? "Internal Server Error" : "Request Refused";
        };
    }
}
