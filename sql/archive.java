package org.skypulse.handlers.settings;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * GET active system settings
 */
public class GetActiveSystemSettingsHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        try (Connection conn = DatabaseManager.getDataSource().getConnection()) {
            String sql = """
                SELECT *
                FROM system_settings
                WHERE is_active = TRUE
                LIMIT 1
            """;

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("system_setting_id", rs.getLong("system_setting_id"));
                    result.put("uptime_check_interval", rs.getInt("uptime_check_interval"));
                    result.put("uptime_retry_count", rs.getInt("uptime_retry_count"));
                    result.put("uptime_retry_delay", rs.getInt("uptime_retry_delay"));
                    result.put("sse_push_interval", rs.getInt("sse_push_interval"));
                    result.put("ssl_check_interval", rs.getInt("ssl_check_interval"));
                    result.put("ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"));
                    result.put("ssl_retry_count", rs.getInt("ssl_retry_count"));
                    result.put("ssl_retry_delay", rs.getInt("ssl_retry_delay"));
                    result.put("notification_check_interval", rs.getInt("notification_check_interval"));
                    result.put("notification_retry_count", rs.getInt("notification_retry_count"));
                    result.put("notification_cooldown_minutes", rs.getInt("notification_cooldown_minutes"));
                    result.put("version", rs.getInt("version"));
                    result.put("is_active", rs.getBoolean("is_active"));
                    result.put("changed_by", rs.getObject("changed_by") != null ? rs.getLong("changed_by") : null);
                    result.put("date_created", rs.getTimestamp("date_created"));
                    result.put("date_modified", rs.getTimestamp("date_modified"));

                    ResponseUtil.sendJson(exchange, StatusCodes.OK, JsonUtil.mapper().writeValueAsString(result));
                } else {
                    ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "No active system settings found");
                }
            }
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
