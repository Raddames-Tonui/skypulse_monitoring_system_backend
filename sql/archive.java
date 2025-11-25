package org.skypulse.handlers;

import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.security.KeyProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SseHealthCheckHandler implements ServerSentEventConnectionCallback {

    private static final Instant START_TIME = Instant.now();
    private final Set<ServerSentEventConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ServerSentEventHandler handler;

    public SseHealthCheckHandler() {
        this.handler = new ServerSentEventHandler(this);
        // Start a background task to push updates periodically
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Update interval can be adjusted
        scheduler.scheduleAtFixedRate(this::pushHealthStatusToAll, 0, 5, TimeUnit.SECONDS);
    }

    public ServerSentEventHandler getHandler() {
        return handler;
    }

    @Override
    public void connected(ServerSentEventConnection connection) {
        connections.add(connection);
        // Add a close task to clean up when a client disconnects
        connection.addCloseTask(conn -> connections.remove(conn));
        // Send initial status immediately upon connection
        pushHealthStatus(connection);
        System.out.println("New SSE connection established. Total connections: " + connections.size());
    }

    private void pushHealthStatusToAll() {
        for (ServerSentEventConnection connection : connections) {
            pushHealthStatus(connection);
        }
    }

    private void pushHealthStatus(ServerSentEventConnection connection) {
        Map<String, Object> status = generateCurrentStatus();
        // Convert map to a JSON string or similar format for the message data
        String jsonData = generateJson(status);
        connection.send(jsonData);
    }

    // Simplified health status generation (without background tasks query for brevity)
    private Map<String, Object> generateCurrentStatus() {
        // ... (Logic from your original handler to get basic info) ...
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("app", "SkyPulse REST API");
        response.put("version", "1.0.0");
        response.put("environment", KeyProvider.getEnvironment());
        response.put("uptime_seconds", Duration.between(START_TIME, Instant.now()).toSeconds());
        response.put("timestamp", Instant.now().toString());

        // Database health check
        boolean dbOK = false;
        if (DatabaseManager.isInitialized()) {
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                dbOK = conn.isValid(2);
            } catch (SQLException ignored) {}
        }
        response.put("database_status", dbOK ? "connected" : "unavailable");

        return response;
    }

    // Placeholder for your JSON serialization logic
    private String generateJson(Map<String, Object> data) {
        // Use your existing JsonUtil or GSON/Jackson to serialize the map
        return org.skypulse.utils.JsonUtil.toJson(data);
    }
}
</code>

        ### 2. Update Your Routing

In your main application setup (where you define your paths and handlers), you would replace the original handler with the new SSE handler:

        ```java
import io.undertow.Handlers;
import io.undertow.Undertow;
// ... other imports

public class App {
    public static void main(String[] args) {
        SseHealthCheckHandler sseHealthHandler = new SseHealthCheckHandler();

        Undertow server = Undertow.builder()
                .addHttpListener(8080, "localhost")
                .setHandler(Handlers.path()
                                // Original health endpoint
                                // .addExactPath("/api/health", new HealthCheckHandler())

                                // New SSE endpoint
                                .addExactPath("/api/health-stream", sseHealthHandler.getHandler())
                        // ... other paths
                ).build();

        server.start();
    }
}
</code>

        ### Client Side (JavaScript)

Your client-side code will now use the browser's built-in `EventSource` API to listen for these updates:

        ```html
        <!DOCTYPE html>
<html>
<body>
<h1>SSE Health Check Stream</h1>
<pre id="output"></pre>

<script>
// Point the EventSource to the new SSE endpoint
    const eventSource = new EventSource('/api/health-stream');
    const output = document.getElementById('output');

eventSource.onmessage = function(event) {
    // Append new data as it arrives
    output.textContent = "Update received at " + new Date().toLocaleTimeString() + ":\n" +
            JSON.stringify(JSON.parse(event.data), null, 2) + "\n\n" +
            output.textContent;
};

eventSource.onerror = function(err) {
    console.error("EventSource failed:", err);
    eventSource.close();
};
</script>

</body>
</html>
