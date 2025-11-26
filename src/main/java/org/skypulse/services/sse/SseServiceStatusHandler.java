package org.skypulse.services.sse;

import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.SystemSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.skypulse.rest.auth.RequireRoles;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@RequireRoles({"ADMIN", "OPERATOR", "VIEWER"})
public class SseServiceStatusHandler extends SseHandler {

    private final Logger logger = LoggerFactory.getLogger(SseServiceStatusHandler.class);

    public SseServiceStatusHandler() throws Exception {
        super(0, SystemSettings.loadSystemDefaults().ssePushInterval());
    }

    @Override
    protected Map<String, Object> generateData() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> services = new ArrayList<>();
        int totalServices = 0, upCount = 0, downCount = 0, sslWarnings = 0;

        String sql = """
            SELECT ms.uuid,
                   ms.monitored_service_id,
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
                service.put("uuid", rs.getObject("uuid"));
                service.put("name", rs.getString("monitored_service_name"));
                service.put("status", status);
                service.put("response_time_ms", rs.getObject("response_time_ms"));
                service.put("ssl_warning", sslWarning);

                services.add(service);
            }

        } catch (SQLException e) {
            logger.error("Error fetching service status for SSE: {}", e.getMessage(), e);
        }

        SystemSettings.SystemDefaults systemDefaults;
        try {
            systemDefaults = SystemSettings.loadSystemDefaults();
        } catch (Exception e) {
            logger.error("Failed to load system defaults", e);
            systemDefaults = null;
        }

        response.put("timestamp", Instant.now().toString());
        response.put("total_services", totalServices);
        response.put("up_count", upCount);
        response.put("down_count", downCount);
        response.put("ssl_warnings", sslWarnings);
        response.put("services", services);
        response.put("sse_push_interval_seconds", systemDefaults != null
                ? systemDefaults.ssePushInterval()
                : 60);

        return response;
    }
}
