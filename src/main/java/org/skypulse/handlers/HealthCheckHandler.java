package org.skypulse.handlers;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.KeyProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * HTTP handler for health check endpoint.
 * Returns  -  basic app info,
 *          -  database status,
 *          -  background task statuses.
 */
@RequireRoles({"ADMIN", "OPERATOR", "VIEWER"})
public class HealthCheckHandler implements HttpHandler {

    private static final Instant START_TIME = Instant.now();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("app", "SkyPulse REST API");
        response.put("version", "1.0.0");
        response.put("environment", KeyProvider.getEnvironment());
        response.put("uptime_seconds", Duration.between(START_TIME, Instant.now()).toSeconds());
        response.put("timestamp", Instant.now().toString());

        // Database health
        boolean dbOK = false;
        if (DatabaseManager.isInitialized()) {
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                dbOK = conn.isValid(2);
            } catch (SQLException ignored) {}
        }
        response.put("database", "PostgreSQL");
        response.put("database_status", dbOK ? "connected" : "unavailable");

        // Background tasks status
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (dbOK) {
            try (Connection conn = DatabaseManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT task_name, task_type, status, last_run_at, next_run_at, error_message " +
                                 "FROM background_tasks ORDER BY task_name"
                 );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> task = new HashMap<>();
                    task.put("name", rs.getString("task_name"));
                    task.put("type", rs.getString("task_type"));
                    task.put("status", rs.getString("status"));
                    task.put("last_run_at", rs.getTimestamp("last_run_at"));
                    task.put("next_run_at", rs.getTimestamp("next_run_at"));
                    task.put("error_message", rs.getString("error_message"));
                    tasks.add(task);
                }

            } catch (SQLException e) {
                response.put("tasks_error", "Failed to query background tasks: " + e.getMessage());
            }
        }

        response.put("background_tasks", tasks);

        ResponseUtil.sendSuccess(exchange, "Health check completed", response);
    }
}
