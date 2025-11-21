package org.skypulse.handlers.settings;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.handlers.services.MonitoredServiceHandler;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Objects;

public class SystemSettingsHandlers  implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettingsHandlers.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        if (!"ADMIN".equals(ctx.getRoleName())) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "Insufficient permissions");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid or empty JSON body");
            return;
        }

        String key = (String) body.get("key");
        if (key == null || key.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required field: key");
            return;
        }

        Integer uptimeCheckInterval = (Integer) body.get("uptime_check_interval");
        Integer uptimeRetryCount = (Integer) body.get("uptime_retry_count");
        Integer uptimeRetryDelay = (Integer) body.get("uptime_retry_delay");
        Integer sslCheckInterval = (Integer) body.get("ssl_check_interval");
        String sslAlertThresholds = (String) body.get("ssl_alert_thresholds");
        Integer notificationRetryCount = (Integer) body.get("notification_retry_count");
        String value = (String) body.get("value");
        String description = (String) body.get("description");

        logger.info("Admin {} upserting system setting '{}'", ctx.getEmail(), key);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // Build dynamic SQL to upsert only provided fields, leaving others untouched
            String sql = """
                INSERT INTO system_settings
                (key, value, description, uptime_check_interval, uptime_retry_count,
                 uptime_retry_delay, ssl_check_interval, ssl_alert_thresholds, notification_retry_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (key) DO UPDATE
                SET value = COALESCE(EXCLUDED.value, system_settings.value),
                    description = COALESCE(EXCLUDED.description, system_settings.description),
                    uptime_check_interval = COALESCE(EXCLUDED.uptime_check_interval, system_settings.uptime_check_interval),
                    uptime_retry_count = COALESCE(EXCLUDED.uptime_retry_count, system_settings.uptime_retry_count),
                    uptime_retry_delay = COALESCE(EXCLUDED.uptime_retry_delay, system_settings.uptime_retry_delay),
                    ssl_check_interval = COALESCE(EXCLUDED.ssl_check_interval, system_settings.ssl_check_interval),
                    ssl_alert_thresholds = COALESCE(EXCLUDED.ssl_alert_thresholds, system_settings.ssl_alert_thresholds),
                    notification_retry_count = COALESCE(EXCLUDED.notification_retry_count, system_settings.notification_retry_count),
                    date_modified = NOW()
                RETURNING system_setting_id;
            """;

            PreparedStatement ps = conn.prepareStatement(sql);
            MonitoredServiceHandler.monitoredServiceDto(key, value, description, uptimeCheckInterval, uptimeRetryCount, uptimeRetryDelay, sslCheckInterval, ps);
            ps.setString(8, sslAlertThresholds);
            ps.setObject(9, notificationRetryCount);

            var rs = ps.executeQuery();
            if (rs.next()) {
                long settingId = rs.getLong(1);
                ResponseUtil.sendSuccess(exchange, "System setting upserted successfully",
                        Map.of("system_setting_id", settingId));
                logger.info("System setting '{}' upserted with ID {}", key, settingId);
            } else {
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to upsert system setting");
            }

        } catch (Exception e) {
            logger.error("Failed to upsert system setting '{}': {}", key, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
