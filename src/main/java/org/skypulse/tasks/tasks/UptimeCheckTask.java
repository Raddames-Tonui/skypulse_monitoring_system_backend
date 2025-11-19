package org.skypulse.tasks.tasks;

import org.skypulse.tasks.ScheduledTask;
import org.skypulse.utils.JdbcUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * UptimeCheckTask:
 * 1. Queues pending tasks for all active monitored services.
 * 2. Executes UPTIME_CHECK tasks from background_tasks table.
 * 3. Writes results to uptime_logs and system_health.
 */
public class UptimeCheckTask implements ScheduledTask {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() { return "UptimeCheckTask"; }

    @Override
    public long intervalSeconds() { return 60; } // every minute

    @Override
    public void execute() {
        try (Connection c = JdbcUtils.getConnection()) {
            c.setAutoCommit(false);
            queuePendingTasks(c);
            processPendingTasks(c);
            c.commit();
        } catch (Exception e) {
            System.err.println("[UptimeCheck] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Step 1: Queue UPTIME_CHECK tasks for all active services if none pending.
     */
    private void queuePendingTasks(Connection c) throws SQLException {
        String selectServices = "SELECT monitored_service_id, monitored_service_name, monitored_service_url, check_interval, retry_count " +
                "FROM monitored_services WHERE is_active = TRUE";
        String checkPending = "SELECT COUNT(*) FROM background_tasks WHERE task_type='UPTIME_CHECK' AND status='PENDING' AND task_name=?";
        String insertTask = "INSERT INTO background_tasks(task_name, task_type, status, next_run_at, date_created) VALUES (?, 'UPTIME_CHECK', 'PENDING', now(), now())";

        try (PreparedStatement psServices = c.prepareStatement(selectServices);
             ResultSet rs = psServices.executeQuery()) {

            while (rs.next()) {
                String serviceName = rs.getString("monitored_service_name");
                String serviceUrl  = rs.getString("monitored_service_url");
                int retryCount     = rs.getInt("retry_count");

                // check if a pending task already exists
                try (PreparedStatement psCheck = c.prepareStatement(checkPending)) {
                    psCheck.setString(1, serviceUrl);
                    try (ResultSet rsCheck = psCheck.executeQuery()) {
                        if (rsCheck.next() && rsCheck.getInt(1) == 0) {
                            // insert new background task
                            try (PreparedStatement psInsert = c.prepareStatement(insertTask)) {
                                psInsert.setString(1, serviceUrl);
                                psInsert.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Step 2: Process pending tasks from background_tasks table.
     */
    private void processPendingTasks(Connection c) throws SQLException {
        String selectTasks = "SELECT background_task_id, task_name FROM background_tasks " +
                "WHERE task_type='UPTIME_CHECK' AND status='PENDING' FOR UPDATE SKIP LOCKED";

        try (PreparedStatement ps = c.prepareStatement(selectTasks);
             ResultSet rs = ps.executeQuery()) {

            List<Long> processed = new ArrayList<>();
            while (rs.next()) {
                long taskId = rs.getLong("background_task_id");
                String url  = rs.getString("task_name");
                boolean up  = checkUrl(url);

                // write to system_health
                writeSystemHealth(c, url, up ? "UP" : "DOWN");

                // write to uptime_logs (optional: store response_time etc.)
                writeUptimeLog(c, url, up);

                // mark task processed
                markTaskProcessed(c, taskId);
                processed.add(taskId);
            }
            if (!processed.isEmpty()) System.out.println("[UptimeCheck] processed tasks: " + processed);
        }
    }

    private boolean checkUrl(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeSystemHealth(Connection c, String key, String state) throws SQLException {
        String upsert = "INSERT INTO system_health(key, value, updated_at) VALUES (?, to_jsonb(?::text)::jsonb, now()) " +
                "ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()";
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            String json = String.format("{\"status\":\"%s\",\"checked_at\":\"%s\"}", state, OffsetDateTime.now());
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    private void writeUptimeLog(Connection c, String url, boolean up) throws SQLException {
        String serviceIdSql = "SELECT monitored_service_id FROM monitored_services WHERE monitored_service_url=?";
        long serviceId;
        try (PreparedStatement ps = c.prepareStatement(serviceIdSql)) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) serviceId = rs.getLong("monitored_service_id");
                else return; // service removed
            }
        }

        String insertLog = "INSERT INTO uptime_logs(service_id, status, checked_at, date_created, date_modified) " +
                "VALUES (?, ?, now(), now(), now())";
        try (PreparedStatement ps = c.prepareStatement(insertLog)) {
            ps.setLong(1, serviceId);
            ps.setString(2, up ? "UP" : "DOWN");
            ps.executeUpdate();
        }
    }

    private void markTaskProcessed(Connection c, long id) throws SQLException {
        String sql = "UPDATE background_tasks SET status='PROCESSED', last_run_at=now(), date_modified=now() WHERE background_task_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void markTaskFailed(Connection c, long id, String reason) throws SQLException {
        String sql = "UPDATE background_tasks SET status='FAILED', last_run_at=now(), error_message=?, date_modified=now() WHERE background_task_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
