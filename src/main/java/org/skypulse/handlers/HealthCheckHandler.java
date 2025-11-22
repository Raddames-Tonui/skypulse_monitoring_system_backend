package org.skypulse.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.security.KeyProvider;
import org.skypulse.utils.JsonUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class HealthCheckHandler implements HttpHandler {
    private static final Instant START_TIME = Instant.now();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("app", "SkyPulse REST API");
        status.put("version", "1.0.0");
        status.put("environment", KeyProvider.getEnvironment());
        status.put("uptime_seconds", Duration.between(START_TIME, Instant.now()).toSeconds());
        status.put("timestamp", Instant.now().toString());

        // DB connection health
        boolean dbOK = false;
        if (DatabaseManager.isInitialized()) {
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                dbOK = conn.isValid(2);
            } catch (SQLException ignored) {}
        }
        status.put("database", dbOK ? "connected" : "unavailable");

        String json = JsonUtil.mapper().writeValueAsString(status);
        exchange.getResponseSender().send(json);
    }
}
