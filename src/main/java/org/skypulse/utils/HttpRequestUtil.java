package org.skypulse.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Parsing JSON, extracting fields, and serialization
 */
public class HttpRequestUtil {

    public static Map<String, Object> parseJson(HttpServerExchange exchange)  {
        try (InputStream is = exchange.getInputStream()) {
            return JsonUtil.mapper().readValue(is, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    public static String getString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value != null ? value.toString() : null;
    }

    public static Integer getInteger(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    public static Long getLong(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    // Convert a Map<String, Object> to JSON string
    public static String toJsonString(Map<String, Object> map) {
        try {
            return JsonUtil.mapper().writeValueAsString(map);
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert map to JSON", e);
        }
    }

    // Read nested Maps eg Map<String, Map<String, Object>> to JSON string
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if(value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }
/**
 *  HTTP request is handled on a thread. There are two main kinds of threads:
 *      - IO threads – handle reading the incoming request and writing the response.
 *      - Worker threads – handle the actual heavy work (like querying a database).

 *      Check if the exchange is running on an IO thread.
 *      If yes, dispatches the handler to a worker thread to safely perform blocking operations.
 *      Prevents blocking the IO thread which handles incoming requests.
 *      Usage:
 *           if (HttpRequestUtil.dispatchIfIoThread(exchange, this)) return;

 * */
    public static boolean dispatchIfIoThread(HttpServerExchange exchange, HttpHandler handler) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(handler); // moves execution to a worker thread
            return true;
        }
        return false;                  // already on worker thread, safe to continue
    }
}
