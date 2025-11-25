package org.skypulse.handlers.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RequireRoles({"ADMIN", "OPERATOR"})
public class SseServiceStatusHandler implements ServerSentEventConnectionCallback {

    private final Logger logger = LoggerFactory.getLogger(SseServiceStatusHandler.class);
    private final Set<ServerSentEventConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ServerSentEventHandler handler;
    private final ScheduledExecutorService scheduler;

    public SseServiceStatusHandler() {
        this.handler = new ServerSentEventHandler(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        // Push updates every 5 seconds
        this.scheduler.scheduleAtFixedRate(this::pushServiceStatusToAll, 0, 5, TimeUnit.SECONDS);
    }

    public ServerSentEventHandler getHandler() {
        return handler;
    }

    public void shutdown() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            logger.info("SSE Service Status scheduler shut down.");
        }
    }

    @Override
    public void connected(ServerSentEventConnection connection, String lastEventId) {
        connections.add(connection);
        connection.addCloseTask(connections::remove);
        pushServiceStatus(connection);
        logger.info("New SSE connection established. Total connections: {}", connections.size());
    }


    private void pushServiceStatusToAll() {
        Map<String, Object> status = fetchCurrentServiceStatus();
        String jsonData = toJson(status);
        if (jsonData != null) {
            for (ServerSentEventConnection connection : connections) {
                if (connection.isOpen()) {
                    connection.send(jsonData);
                }
            }
        }
    }

    private void pushServiceStatus(ServerSentEventConnection connection) {
        Map<String, Object> status = fetchCurrentServiceStatus();
        String jsonData = toJson(status);
        if (jsonData != null) {
            connection.send(jsonData);
        }
    }

    private Map<String, Object> fetchCurrentServiceStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> services = new ArrayList<>();
        int totalServices = 0;
        int upCount = 0;
        int downCount = 0;
        int sslWarnings = 0;

        String sql = """
            SELECT ms.monitored_service_id,
                   ms.monitored_service_name,
                   ms.last_uptime_status,
                   ul.response_time_ms,
                   sl.days_remaining
            FROM monitored_services ms
            LEFT JOIN LATERAL (
                SELECT response_time_ms
                FROM uptime_logs
                WHERE monitored_service_id = ms.monitored_service_id
                ORDER BY checked_at DESC
                LIMIT 1
            ) ul ON true
            LEFT JOIN LATERAL (
                SELECT days_remaining
                FROM ssl_logs
                WHERE monitored_service_id = ms.monitored_service_id
                ORDER BY last_checked DESC
                LIMIT 1
            ) sl ON true
            WHERE ms.is_active = TRUE
            """;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                totalServices++;
                String status = rs.getString("last_uptime_status");
                if ("UP".equalsIgnoreCase(status)) upCount++;
                if ("DOWN".equalsIgnoreCase(status)) downCount++;

                Integer daysRemaining = rs.getObject("days_remaining", Integer.class);
                boolean sslWarning = daysRemaining != null && daysRemaining <= 30;
                if (sslWarning) sslWarnings++;

                Map<String, Object> service = new HashMap<>();
                service.put("monitored_service_id", rs.getLong("monitored_service_id"));
                service.put("monitored_service_name", rs.getString("monitored_service_name"));
                service.put("status", status);
                service.put("response_time_ms", rs.getObject("response_time_ms"));
                service.put("ssl_warning", sslWarning);

                services.add(service);
            }

        } catch (SQLException e) {
            logger.error("Error fetching service status for SSE: {}", e.getMessage(), e);
        }

        response.put("timestamp", Instant.now().toString());
        response.put("total_services", totalServices);
        response.put("up_count", upCount);
        response.put("down_count", downCount);
        response.put("ssl_warnings", sslWarnings);
        response.put("services", services);

        return response;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return JsonUtil.mapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing service status SSE payload: {}", e.getMessage());
            return null;
        }
    }
}
