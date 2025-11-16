package org.skypulse.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.HashMap;
import java.util.Map;

public class ResponseUtil {

    private static final ObjectMapper mapper = JsonUtil.mapper();

    public static void sendJson(HttpServerExchange exchange, int status, Object body) {
        try {
            exchange.setStatusCode(status);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

            String json = mapper.writeValueAsString(body);
            exchange.getResponseSender().send(json);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static void sendError(HttpServerExchange exchange, int status, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "error");
        res.put("message", message);

        sendJson(exchange, status, res);
    }

    public static void sendCreated(HttpServerExchange exchange, Object data) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "created");
        res.put("data", data);

        sendJson(exchange, 201, res);
    }
}
