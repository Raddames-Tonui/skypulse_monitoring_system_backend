package org.skypulse.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResponseUtil {
    private static final Logger logger = LoggerFactory.getLogger(ResponseUtil.class);

    private static final ObjectMapper mapper = JsonUtil.mapper();

    public static void sendJson(HttpServerExchange exchange, int status, Map<String, Object> body) {
        try {
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            String json = mapper.writeValueAsString(body);
            exchange.getResponseSender().send(json);

            logger.debug("Response sent. Status: {}, Body: {}", status, json);
        } catch (Exception e) {
            logger.error("Failed to send JSON response. Status: {}, Body: {}. Exception: {}", status, body, e.getMessage(), e);
        }
    }


    public static void sendError(HttpServerExchange exchange, int status, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "error");
        res.put("message", message);
        sendJson(exchange, status, res);
    }

    public static void sendSuccess(HttpServerExchange exchange, String message, Object data) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("message", message);
        if (data != null) {
            res.put("data", data);
        }
        sendJson(exchange, 200, res);
    }

    public static void sendCreated(HttpServerExchange exchange, String message, Object data) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("message", message);
        if (data != null) {
            res.put("data", data);
        }
        sendJson(exchange, 201, res);
    }


    public static void sendPaginated(HttpServerExchange exchange, int page, int size, int total, List<?> items, String key) {
        Map<String, Object> res = new HashMap<>();
        res.put("page", page);
        res.put("size", size);
        res.put("total", total);
        res.put("pages", (int) Math.ceil((double) total / size));
        res.put(key, items);
        sendJson(exchange, 200, res);
    }

}
