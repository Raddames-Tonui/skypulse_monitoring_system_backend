package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * UptimeCheckTask: monitors a single service.
 */
public class UptimeCheckTask implements ScheduledTask {
    private static final Logger logger = LoggerFactory.getLogger(UptimeCheckTask.class);

    private final long serviceId;
    private final String serviceName;
    private final String serviceUrl;
    private final int intervalSeconds;
    private final int retryCount;
    private final int retryDelay;
    private final int expectedStatusCode;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UptimeCheckTask(long serviceId, String serviceName, String serviceUrl,
                           int intervalMinutes, int retryCount, int retryDelay, int expectedStatusCode) {
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.serviceUrl = serviceUrl;
        this.intervalSeconds = intervalMinutes * 60; // convert to seconds
        this.retryCount = retryCount;
        this.retryDelay = retryDelay;
        this.expectedStatusCode = expectedStatusCode;
    }

    @Override
    public String name() {
        return "UptimeCheckTask-" + serviceId;
    }

    @Override
    public long intervalSeconds() {
        return intervalSeconds;
    }

    @Override
    public void execute() {
        try (Connection c = JdbcUtils.getConnection()) {
            c.setAutoCommit(false);
            checkService(c);
            c.commit();
        } catch (Exception e) {
            logger.error("Error executing uptime check for service {} ({})", serviceName, serviceId, e);
        }
    }

    private void checkService(Connection c) throws SQLException, InterruptedException {
        String status = "DOWN";
        String errorMessage = null;
        int httpCode = -1;
        long responseTime = -1;

        // retry loop
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            long start = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serviceUrl))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .GET()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                responseTime = System.currentTimeMillis() - start;
                httpCode = response.statusCode();

                if (httpCode == expectedStatusCode || (httpCode >= 200 && httpCode < 400)) {
                    status = "UP";
                    errorMessage = null;
                    break;
                } else {
                    errorMessage = "Unexpected HTTP code: " + httpCode;
                }
            } catch (Exception e) {
                responseTime = System.currentTimeMillis() - start;
                errorMessage = e.getMessage();
            }

            if (attempt < retryCount) Thread.sleep(retryDelay * 1000L);
        }

        // fetch previous state
        String oldStatus = getPreviousStatus(c);

        // log uptime
        logUptime(c, status, responseTime, httpCode, errorMessage);

        // create event if status changed
        if (!status.equals(oldStatus)) {
            createEvent(c, oldStatus, status, errorMessage);
        }

        logger.info("Service '{}' checked: status={}, responseTime={}ms, httpCode={}, error={}",
                serviceName, status, responseTime, httpCode, errorMessage);
    }

    private String getPreviousStatus(Connection c) throws SQLException {
        String sql = "SELECT status FROM uptime_logs WHERE monitored_service_id=? ORDER BY checked_at DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("status");
            }
        }
        return "UNKNOWN";
    }

    private void logUptime(Connection c, String status, long responseTime, int httpCode, String errorMessage) throws SQLException {
        String sql = "INSERT INTO uptime_logs(monitored_service_id, status, response_time_ms, http_status, error_message, checked_at, date_created, date_modified) " +
                "VALUES (?, ?, ?, ?, ?, NOW(), NOW(), NOW())";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setString(2, status);
            ps.setObject(3, responseTime, Types.INTEGER);
            ps.setObject(4, httpCode, Types.INTEGER);
            ps.setString(5, errorMessage);
            ps.executeUpdate();
        }
    }

    private void createEvent(Connection c, String oldStatus, String newStatus, String errorMessage) throws SQLException {
        String eventType = newStatus.equals("DOWN") ? "SERVICE_DOWN" : "SERVICE_RECOVERED";

        Map<String, Object> payload = new HashMap<>();
        payload.put("service_id", serviceId);
        payload.put("service_name", serviceName);
        payload.put("old_status", oldStatus);
        payload.put("new_status", newStatus);
        payload.put("error_message", errorMessage);
        payload.put("checked_at", OffsetDateTime.now().toString());

        String sql = "INSERT INTO event_outbox(event_type, payload, status, retries, created_at, updated_at) " +
                "VALUES (?, ?::jsonb, 'PENDING', 0, NOW(), NOW())";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to create event for service {} ({})", serviceName, serviceId, e);
        }
    }
}
