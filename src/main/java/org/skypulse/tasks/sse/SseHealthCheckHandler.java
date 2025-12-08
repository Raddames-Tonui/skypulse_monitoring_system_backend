package org.skypulse.tasks.sse;

import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.utils.security.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.skypulse.rest.auth.RequireRoles;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RequireRoles({"ADMIN", "OPERATOR", "VIEWER"})
public class SseHealthCheckHandler extends SseHandler {

    private static final Instant START_TIME = Instant.now();
    private final Logger logger = LoggerFactory.getLogger(SseHealthCheckHandler.class);

    public SseHealthCheckHandler() throws Exception {
        super(0, SystemSettings.loadSystemDefaults().ssePushInterval());
    }

    @Override
    protected Map<String, Object> generateData() {
        Map<String, Object> response = new LinkedHashMap<>();

        SystemSettings.SystemDefaults systemDefaults;
        try {
            systemDefaults = SystemSettings.loadSystemDefaults();
        } catch (Exception e) {
            logger.error("Failed to load system defaults", e);
            systemDefaults = null;
        }

        // Basic system info
        response.put("app", "SkyPulse REST API");
        response.put("version", "1.0.0");
        response.put("environment", KeyProvider.getEnvironment());
        response.put("uptime_seconds", Duration.between(START_TIME, Instant.now()).toSeconds());
        response.put("timestamp", Instant.now().toString());

        // Database status
        boolean dbOK = false;
        if (DatabaseManager.isInitialized()) {
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                dbOK = conn.isValid(2);
            } catch (SQLException ignored) {}
        }
        response.put("database", "PostgreSQL");
        response.put("database_status", dbOK ? "connected" : "unavailable");

        // SSE push interval
        response.put("sse_push_interval_seconds", systemDefaults != null
                ? systemDefaults.ssePushInterval()
                : 60);

        return response;
    }
}
