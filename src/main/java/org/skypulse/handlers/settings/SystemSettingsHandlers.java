package org.skypulse.handlers.settings;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Objects;

/**
 * INSERT / UPDATE system_settings table.
 * Has versions. Only one row is active at a time.
 */
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
            String beforeData = null;

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
                        beforeData = String.format("""
                                {"uptime_check_interval": %d, "uptime_retry_count": %d, "uptime_retry_delay": %d,
                                 "sse_push_interval": %d, "ssl_check_interval": %d, "ssl_alert_thresholds": "%s",
                                 "ssl_retry_count": %d, "ssl_retry_delay": %d,
                                 "notification_check_interval": %d, "notification_retry_count": %d,
                                 "notification_cooldown_minutes": %d, "version": %d}
                                """,
                                rs.getInt("uptime_check_interval"),
                                rs.getInt("uptime_retry_count"),
                                rs.getInt("uptime_retry_delay"),
                                rs.getInt("sse_push_interval"),
                                rs.getInt("ssl_check_interval"),
                                rs.getString("ssl_alert_thresholds"),
                                rs.getInt("ssl_retry_count"),
                                rs.getInt("ssl_retry_delay"),
                                rs.getInt("notification_check_interval"),
                                rs.getInt("notification_retry_count"),
                                rs.getInt("notification_cooldown_minutes"),
                                rs.getInt("version")
                        );
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

            AuditLogger.log(exchange, "system_settings", newId, "UPDATE", beforeData != null ? beforeData : "{}",body.toString());

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

            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT system_setting_id, version
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
                }
            }

            if (currentVersion <= 1) {
                ResponseUtil.sendError(exchange, 400, "No previous version to rollback to");
                return;
            }

            Long previousId = null;
            String previousData = null;

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
                        previousData = String.format("""
                            {"uptime_check_interval": %d, "uptime_retry_count": %d, "uptime_retry_delay": %d,
                             "sse_push_interval": %d, "ssl_check_interval": %d, "ssl_alert_thresholds": "%s",
                             "ssl_retry_count": %d, "ssl_retry_delay": %d,
                             "notification_check_interval": %d, "notification_retry_count": %d,
                             "notification_cooldown_minutes": %d, "version": %d}
                            """,
                                rs.getInt("uptime_check_interval"),
                                rs.getInt("uptime_retry_count"),
                                rs.getInt("uptime_retry_delay"),
                                rs.getInt("sse_push_interval"),
                                rs.getInt("ssl_check_interval"),
                                rs.getString("ssl_alert_thresholds"),
                                rs.getInt("ssl_retry_count"),
                                rs.getInt("ssl_retry_delay"),
                                rs.getInt("notification_check_interval"),
                                rs.getInt("notification_retry_count"),
                                rs.getInt("notification_cooldown_minutes"),
                                rs.getInt("version")
                        );
                    }
                }
            }

            if (previousId == null) {
                ResponseUtil.sendError(exchange, 400, "Previous version not found, rollback impossible");
                return;
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE system_settings
                SET is_active = FALSE
                WHERE system_setting_id = ?
        """)) {
                ps.setLong(1, currentId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE system_settings
                SET is_active = TRUE, date_modified = NOW()
                WHERE system_setting_id = ?
        """)) {
                ps.setLong(1, previousId);
                ps.executeUpdate();
            }

            conn.commit();

            AuditLogger.log(exchange, "system_settings", previousId, "ROLLBACK", previousData, null);

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
