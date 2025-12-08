package org.skypulse.tasks.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skypulse.tasks.ScheduledTask;
import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;



public class UptimeCheckTask implements ScheduledTask {

    private static final ObjectMapper mapper = JsonUtil.mapper();


    private static final Logger logger = LoggerFactory.getLogger(UptimeCheckTask.class);
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final SystemSettings.ServiceConfig service;

    private final int intervalSeconds;
    private final int retryCount;
    private final int retryDelay;
    private final int expectedStatusCode;

    public UptimeCheckTask(SystemSettings.ServiceConfig service,
                           SystemSettings.SystemDefaults defaults) {
        this.service = service;
        this.intervalSeconds = service.checkInterval() > 0 ? service.checkInterval() * 60 : defaults.uptimeCheckInterval() * 60;
        this.retryCount = service.retryCount() > 0 ? service.retryCount() : defaults.uptimeRetryCount();
        this.retryDelay = service.retryDelay() > 0 ? service.retryDelay() : defaults.uptimeRetryDelay();
        this.expectedStatusCode = service.expectedStatusCode() > 0 ? service.expectedStatusCode() : 200;
    }

    @Override
    public String name() {
        return "[ UptimeCheckTask ] " + service.serviceName();
    }

    @Override
    public long intervalSeconds() {
        return intervalSeconds;
    }

    @Override
    public void execute() {
        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);
            performCheck(conn);
            conn.commit();
        } catch (Exception e) {
            logger.error("Error executing uptime check for service {} ({})", service.serviceName(), service.serviceId(), e);
        }
    }

    private void performCheck(Connection conn) throws Exception {

        String status = "DOWN";
        String errorMessage = null;
        int httpCode = -1;
        long responseTime = -1;

        for (int attempt = 0; attempt <= retryCount; attempt++) {
            long start = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(service.serviceUrl()))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());

                responseTime = System.currentTimeMillis() - start;
                httpCode = response.statusCode();

                // If success → break immediately
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

            if (attempt < retryCount) {
                Thread.sleep(retryDelay * 1000L);
            }
        }


        String oldStatus = "UNKNOWN";
        int consecutiveFailures = 0;

        String fetchSql = """
        SELECT last_uptime_status, consecutive_failures
        FROM monitored_services
        WHERE monitored_service_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(fetchSql)) {
            ps.setLong(1, service.serviceId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    oldStatus = rs.getString("last_uptime_status");
                    consecutiveFailures = rs.getInt("consecutive_failures");
                }
            }
        }

        if ("UP".equals(status)) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures += 1;
        }

        String updateSql = """
        UPDATE monitored_services
        SET last_uptime_status = ?,
            consecutive_failures = ?,
            last_checked = NOW(),
            date_modified = NOW()
        WHERE monitored_service_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, status);
            ps.setInt(2, consecutiveFailures);
            ps.setLong(3, service.serviceId());
            ps.executeUpdate();
        }


        String insertLog = """
        INSERT INTO uptime_logs(
            monitored_service_id, status, response_time_ms,
            http_status, error_message, checked_at,
            date_created, date_modified
        )
        VALUES (?, ?, ?, ?, ?, NOW(), NOW(), NOW())
        """;

        try (PreparedStatement ps = conn.prepareStatement(insertLog)) {
            ps.setLong(1, service.serviceId());
            ps.setString(2, status);
            ps.setObject(3, responseTime);
            ps.setObject(4, httpCode);
            ps.setString(5, errorMessage);
            ps.executeUpdate();
        }


        boolean createEvent = false;

        // Recovery event: UP after DOWN
        if ("UP".equals(status) && !"UP".equals(oldStatus)) {
            createEvent = true;
        }

        // DOWN event: threshold crossed
        else if ("DOWN".equals(status) && consecutiveFailures == retryCount) {
            createEvent = true;
        }


        if (createEvent) {
            createEvent(conn, oldStatus, status, errorMessage, httpCode, responseTime, retryCount, retryDelay, intervalSeconds);
        }

        logger.info(
                "Service '{}' [{}] checked: status={}, responseTime={}ms, httpCode={}, error={}, consecutiveFailures={}",
                service.serviceName(),
                service.serviceUrl(),
                status,
                responseTime,
                httpCode,
                errorMessage,
                consecutiveFailures
        );
    }

    private void createEvent(Connection conn,
                             String oldStatus,
                             String newStatus,
                             String errorMessage,
                             int httpCode,
                             long responseTime,
                             int retryCount,
                             int retryDelay,
                             int intervalSeconds) {

        String eventType = "DOWN".equals(newStatus)
                ? "SERVICE_DOWN"
                : "SERVICE_RECOVERED";

        Map<String, Object> payload = new HashMap<>();
        payload.put("service_id", service.serviceId());
        payload.put("service_name", service.serviceName());
        payload.put("old_status", oldStatus);
        payload.put("new_status", newStatus);
        payload.put("error_message", errorMessage);
        payload.put("http_code", httpCode);
        payload.put("response_time_ms", responseTime);
        payload.put("retry_count", retryCount);
        payload.put("retry_delay", retryDelay);
        payload.put("interval_seconds", intervalSeconds);
        payload.put("checked_at", OffsetDateTime.now().toString());

        String sql = """
        INSERT INTO event_outbox(
            service_id,
            event_type,
            payload,
            status,
            retries,
            created_at,
            updated_at
        ) VALUES (
            ?, ?, ?::jsonb, 'PENDING', 0, NOW(), NOW()
        )
        """;


        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, service.serviceId());
            ps.setString(2, eventType);
            ps.setString(3, mapper.writeValueAsString(payload));

            int rows = ps.executeUpdate();
            logger.info("Event created: type={} for service {} (rows={})",
                    eventType, service.serviceName(), rows);

        } catch (Exception e) {
            logger.error("Failed to create event for service {} ({})",
                    service.serviceName(), service.serviceId(), e);
        }
    }

}
