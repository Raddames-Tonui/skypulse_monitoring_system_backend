package org.skypulse.handlers.settings;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RequireRoles({"ADMIN"})
public class SystemSettingsHandlers implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON");
            return;
        }

        if (Boolean.TRUE.equals(body.get("rollback"))) {
            rollback(exchange);
            return;
        }

        upsert(exchange, body);
    }

    private void upsert(HttpServerExchange exchange, Map<String, Object> body) {
        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);

            Long currentId = null;
            int nextVersion = 1;
            Map<String, Object> beforeDataMap = null;

            // Fetch current active settings
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT *
                    FROM system_settings
                    WHERE is_active = TRUE
                    LIMIT 1
            """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentId = rs.getLong("system_setting_id");
                        nextVersion = rs.getInt("version") + 1;

                        beforeDataMap = new HashMap<>();
                        beforeDataMap.put("uptime_check_interval", rs.getInt("uptime_check_interval"));
                        beforeDataMap.put("uptime_retry_count", rs.getInt("uptime_retry_count"));
                        beforeDataMap.put("uptime_retry_delay", rs.getInt("uptime_retry_delay"));
                        beforeDataMap.put("sse_push_interval", rs.getInt("sse_push_interval"));
                        beforeDataMap.put("ssl_check_interval", rs.getInt("ssl_check_interval"));
                        beforeDataMap.put("ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"));
                        beforeDataMap.put("ssl_retry_count", rs.getInt("ssl_retry_count"));
                        beforeDataMap.put("ssl_retry_delay", rs.getInt("ssl_retry_delay"));
                        beforeDataMap.put("notification_check_interval", rs.getInt("notification_check_interval"));
                        beforeDataMap.put("notification_retry_count", rs.getInt("notification_retry_count"));
                        beforeDataMap.put("notification_cooldown_minutes", rs.getInt("notification_cooldown_minutes"));
                        beforeDataMap.put("version", rs.getInt("version"));
                    }
                }

            }

            // Deactivate current
            if (currentId != null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE system_settings
                        SET is_active = FALSE, date_modified = NOW()
                        WHERE system_setting_id = ?
                """)) {
                    ps.setLong(1, currentId);
                    ps.executeUpdate();
                }
            }

            // Insert new settings
            long newId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO system_settings (
                        uptime_check_interval, uptime_retry_count, uptime_retry_delay,
                        sse_push_interval,
                        ssl_check_interval, ssl_alert_thresholds, ssl_retry_count, ssl_retry_delay,
                        notification_check_interval, notification_retry_count, notification_cooldown_minutes,
                        version, is_active, changed_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    RETURNING system_setting_id
            """)) {

                int i = 1;
                ps.setObject(i++, body.get("uptime_check_interval"));
                ps.setObject(i++, body.get("uptime_retry_count"));
                ps.setObject(i++, body.get("uptime_retry_delay"));
                ps.setObject(i++, body.get("sse_push_interval"));

                ps.setObject(i++, body.get("ssl_check_interval"));
                ps.setObject(i++, body.get("ssl_alert_thresholds"));
                ps.setObject(i++, body.get("ssl_retry_count"));
                ps.setObject(i++, body.get("ssl_retry_delay"));

                ps.setObject(i++, body.get("notification_check_interval"));
                ps.setObject(i++, body.get("notification_retry_count"));
                ps.setObject(i++, body.get("notification_cooldown_minutes"));

                ps.setInt(i++, nextVersion);

                UserContext userContext = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
                ps.setObject(i, userContext != null ? userContext.userId() : null);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    newId = rs.getLong(1);
                }
            }

            conn.commit();

            // Serialize JSON correctly for audit log
            String beforeDataJson = beforeDataMap != null ? JsonUtil.mapper().writeValueAsString(beforeDataMap) : "{}";
            String afterDataJson  = JsonUtil.mapper().writeValueAsString(body);

            AuditLogger.log(exchange, "system_settings", newId, "UPDATE", beforeDataJson, afterDataJson);

            ResponseUtil.sendSuccess(exchange, "Settings updated", Map.of(
                    "current_version", nextVersion,
                    "new_id", newId,
                    "old_id", currentId
            ));

        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void rollback(HttpServerExchange exchange) {
        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);

            Long currentId = null;
            int currentVersion = -1;
            Map<String, Object> currentDataMap = null;

            // Fetch current active settings
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT *
                FROM system_settings
                WHERE is_active = TRUE
                LIMIT 1
            """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 400, "No active setting found");
                        return;
                    }
                    currentId = rs.getLong("system_setting_id");
                    currentVersion = rs.getInt("version");

                    currentDataMap = new HashMap<>();
                    currentDataMap.put("uptime_check_interval", rs.getInt("uptime_check_interval"));
                    currentDataMap.put("uptime_retry_count", rs.getInt("uptime_retry_count"));
                    currentDataMap.put("uptime_retry_delay", rs.getInt("uptime_retry_delay"));
                    currentDataMap.put("sse_push_interval", rs.getInt("sse_push_interval"));
                    currentDataMap.put("ssl_check_interval", rs.getInt("ssl_check_interval"));
                    currentDataMap.put("ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"));
                    currentDataMap.put("ssl_retry_count", rs.getInt("ssl_retry_count"));
                    currentDataMap.put("ssl_retry_delay", rs.getInt("ssl_retry_delay"));
                    currentDataMap.put("notification_check_interval", rs.getInt("notification_check_interval"));
                    currentDataMap.put("notification_retry_count", rs.getInt("notification_retry_count"));
                    currentDataMap.put("notification_cooldown_minutes", rs.getInt("notification_cooldown_minutes"));
                    currentDataMap.put("version", rs.getInt("version"));

                }
            }

            if (currentVersion <= 1) {
                ResponseUtil.sendError(exchange, 400, "No previous version to rollback to");
                return;
            }

            Long previousId = null;
            Map<String, Object> previousDataMap = null;

            // Fetch previous version
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT *
                FROM system_settings
                WHERE version = ?
                LIMIT 1
            """)) {
                ps.setInt(1, currentVersion - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        previousId = rs.getLong("system_setting_id");
                        previousDataMap = new HashMap<>();
                        previousDataMap.put("uptime_check_interval", rs.getInt("uptime_check_interval"));
                        previousDataMap.put("uptime_retry_count", rs.getInt("uptime_retry_count"));
                        previousDataMap.put("uptime_retry_delay", rs.getInt("uptime_retry_delay"));
                        previousDataMap.put("sse_push_interval", rs.getInt("sse_push_interval"));
                        previousDataMap.put("ssl_check_interval", rs.getInt("ssl_check_interval"));
                        previousDataMap.put("ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"));
                        previousDataMap.put("ssl_retry_count", rs.getInt("ssl_retry_count"));
                        previousDataMap.put("ssl_retry_delay", rs.getInt("ssl_retry_delay"));
                        previousDataMap.put("notification_check_interval", rs.getInt("notification_check_interval"));
                        previousDataMap.put("notification_retry_count", rs.getInt("notification_retry_count"));
                        previousDataMap.put("notification_cooldown_minutes", rs.getInt("notification_cooldown_minutes"));
                        previousDataMap.put("version", rs.getInt("version"));

                    }
                }
            }

            if (previousId == null) {
                ResponseUtil.sendError(exchange, 400, "Previous version not found, rollback impossible");
                return;
            }

            // Deactivate current
            try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE system_settings
                SET is_active = FALSE
                WHERE system_setting_id = ?
            """)) {
                ps.setLong(1, currentId);
                ps.executeUpdate();
            }

            // Activate previous
            try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE system_settings
                SET is_active = TRUE, date_modified = NOW()
                WHERE system_setting_id = ?
            """)) {
                ps.setLong(1, previousId);
                ps.executeUpdate();
            }

            conn.commit();

            // Log rollback with old = current, new = previous
            String beforeDataJson = JsonUtil.mapper().writeValueAsString(currentDataMap);
            String afterDataJson  = JsonUtil.mapper().writeValueAsString(previousDataMap);
            AuditLogger.log(exchange, "system_settings", previousId, "ROLLBACK", beforeDataJson, afterDataJson);

            ResponseUtil.sendSuccess(exchange, "Rolled back to previous version", Map.of(
                    "new_active_id", previousId,
                    "old_deactivated_id", currentId,
                    "version_restored", currentVersion - 1
            ));

        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 500, e.getMessage());
        }
    }
}
