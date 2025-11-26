package org.skypulse.handlers.settings;


import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RequireRoles({"ADMIN"})
public class GetActiveSystemSettingsHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GetActiveSystemSettingsHandler.class);
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()){
            String sql = """
                    SELECT * FROM system_settings WHERE is_active = TRUE LIMIT 1
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)){
                ResultSet rs = ps.executeQuery();
                if (rs.next()){
                    Map<String, Object> response = new HashMap<>();

                    response.put("system_setting_id", rs.getLong("system_setting_id"));
                    response.put("uptime_check_interval", rs.getInt("uptime_check_interval"));
                    response.put("uptime_retry_count", rs.getInt("uptime_retry_count"));
                    response.put("uptime_retry_delay", rs.getInt("uptime_retry_delay"));
                    response.put("sse_push_interval", rs.getInt("sse_push_interval"));
                    response.put("ssl_check_interval", rs.getInt("ssl_check_interval"));
                    response.put("ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"));
                    response.put("ssl_retry_count", rs.getInt("ssl_retry_count"));
                    response.put("ssl_retry_delay", rs.getInt("ssl_retry_delay"));
                    response.put("notification_check_interval", rs.getInt("notification_check_interval"));
                    response.put("notification_retry_count", rs.getInt("notification_retry_count"));
                    response.put("notification_cooldown_minutes", rs.getInt("notification_cooldown_minutes"));
                    response.put("version", rs.getInt("version"));
                    response.put("is_active", rs.getBoolean("is_active"));

                    ResponseUtil.sendSuccess(exchange,"System settings fetched successfully", response);
                } else {
                    ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "No active system settings");
                }
            }
        } catch (Exception e){
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
            logger.error("Failed to process request: {}", e.getMessage(), e);
        }
    }
}