package com.baobabplatform.subscriptions.log;

import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured JSON-lines logging to stdout. Callers pass bounded, non-sensitive
 * fields only: never tokens, request bodies or payment data.
 */
public final class Log {
    private static final PrintStream OUT = System.out;

    private Log() {
    }

    public static void info(String event, Map<String, ?> fields) {
        write("INFO", event, fields);
    }

    public static void warn(String event, Map<String, ?> fields) {
        write("WARN", event, fields);
    }

    public static void error(String event, Map<String, ?> fields) {
        write("ERROR", event, fields);
    }

    private static void write(String level, String event, Map<String, ?> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("level", level);
        line.put("service", "baobab-subscriptions");
        line.put("event", event);
        line.putAll(fields);
        try {
            String json = Json.mapper().writeValueAsString(line);
            synchronized (OUT) {
                OUT.println(json);
            }
        } catch (JsonProcessingException e) {
            synchronized (OUT) {
                OUT.println("{\"level\":\"ERROR\",\"event\":\"log.serialization_failed\"}");
            }
        }
    }
}
