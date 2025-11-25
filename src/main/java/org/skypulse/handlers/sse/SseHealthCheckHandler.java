package org.skypulse.handlers.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.security.KeyProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SseHealthCheckHandler implements ServerSentEventConnectionCallback {

    private static final Instant START_TIME = Instant.now();
    // Use the ConcurrentHashMap directly as the underlying map for thread safety
    private final Set<ServerSentEventConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ServerSentEventHandler handler;

    public SseHealthCheckHandler() {
        this.handler = new ServerSentEventHandler(this);
        // Start a background task to push updates periodically every 5 seconds
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.scheduleAtFixedRate(this::pushHealthStatusToAll, 0, 10, TimeUnit.SECONDS);
        }
    }

    public ServerSentEventHandler getHandler() {
        return handler;
    }

    @Override
    public void connected(ServerSentEventConnection connection, String lastEventId) {
        connections.add(connection);
        // Use method reference for cleaner code
        connection.addCloseTask(connections::remove);
        pushHealthStatus(connection); // Send initial status immediately
        System.out.println("New SSE connection established (Last Event ID: " + lastEventId + "). Total connections: " + connections.size());
    }

    private void pushHealthStatusToAll() {
        // Generate the status once per scheduled run
        Map<String, Object> status = generateCurrentStatus();
        String jsonData = generateJson(status);

        if (jsonData != null) {
            for (ServerSentEventConnection connection : connections) {
                // Check if the connection is still open before sending
                if (connection.isOpen()) {
                    connection.send(jsonData);
                }
            }
        }
    }

    // Renamed from pushHealthStatus to a private method that handles the actual sending
    private void pushHealthStatus(ServerSentEventConnection connection) {
        Map<String, Object> status = generateCurrentStatus();
        String jsonData = generateJson(status);
        if (jsonData != null) {
            connection.send(jsonData);
        }
    }

    /**
     * Gathers all health data, including the detailed background tasks query.
     */
    private Map<String, Object> generateCurrentStatus() {
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
        response.put("database_status", dbOK ? "connected" : "unavailable");

        // Background tasks status (integrated from the original handler)
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (dbOK) {
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
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
                    // Note: Jackson will need to handle java.sql.Timestamp serialization
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

        return response;
    }

    private String generateJson(Map<String, Object> data) {
        try {
            // Use the ObjectMapper instance to write the object as a string
            return JsonUtil.mapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            System.err.println("Error serializing health data: " + e.getMessage());
            return null; // Handle the error gracefully
        }
    }
}
