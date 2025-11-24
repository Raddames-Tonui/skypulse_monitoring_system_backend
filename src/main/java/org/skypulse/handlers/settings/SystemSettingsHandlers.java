package org.skypulse.handlers.settings;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Objects;

/**
 * INSERT / UPDATE system_settings table.
 * - only 1 system_setting column to avoid breakage in services
 * - subsequent updates insert into system_settings_history and versions it
 * */
public class SystemSettingsHandlers implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettingsHandlers.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        if (!"ADMIN".equalsIgnoreCase(ctx.getRoleName())) {
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

        InetSocketAddress addr = exchange.getSourceAddress();
        String ip = addr != null ? addr.getAddress().getHostAddress() : "unknown";

        logger.info("Admin {} upserting system setting '{}'", ctx.getEmail(), key);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            Map<String, Object> before = null;
            long systemSettingId = -1;
            int version = 1;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM system_settings WHERE key = ? AND is_active = TRUE")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        systemSettingId = rs.getLong("system_setting_id");
                        version = rs.getInt("version") + 1;
                        before = Map.of(
                                "key", rs.getString("key"),
                                "value", rs.getString("value"),
                                "description", rs.getString("description"),
                                "uptime_check_interval", rs.getInt("uptime_check_interval"),
                                "uptime_retry_count", rs.getInt("uptime_retry_count"),
                                "uptime_retry_delay", rs.getInt("uptime_retry_delay"),
                                "ssl_check_interval", rs.getInt("ssl_check_interval"),
                                "ssl_alert_thresholds", rs.getString("ssl_alert_thresholds"),
                                "notification_retry_count", rs.getInt("notification_retry_count"),
                                "version", rs.getInt("version")
                        );
                    }
                }
            }

            String action = (before == null) ? "CREATE" : "UPDATE";

            String sql = """
                INSERT INTO system_settings
                        (key, value, description, uptime_check_interval, uptime_retry_count,
                         uptime_retry_delay, ssl_check_interval, ssl_alert_thresholds, notification_retry_count, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (key) DO UPDATE
                        SET value = COALESCE(EXCLUDED.value, system_settings.value),
                            description = COALESCE(EXCLUDED.description, system_settings.description),
                            uptime_check_interval = COALESCE(EXCLUDED.uptime_check_interval, system_settings.uptime_check_interval),
                            uptime_retry_count = COALESCE(EXCLUDED.uptime_retry_count, system_settings.uptime_retry_count),
                            uptime_retry_delay = COALESCE(EXCLUDED.uptime_retry_delay, system_settings.uptime_retry_delay),
                            ssl_check_interval = COALESCE(EXCLUDED.ssl_check_interval, system_settings.ssl_check_interval),
                            ssl_alert_thresholds = COALESCE(EXCLUDED.ssl_alert_thresholds, system_settings.ssl_alert_thresholds),
                            notification_retry_count = COALESCE(EXCLUDED.notification_retry_count, system_settings.notification_retry_count),
                            version = EXCLUDED.version,
                            date_modified = NOW()
                        RETURNING system_setting_id;
            """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, description);
                ps.setObject(4, uptimeCheckInterval);
                ps.setObject(5, uptimeRetryCount);
                ps.setObject(6, uptimeRetryDelay);
                ps.setObject(7, sslCheckInterval);
                ps.setString(8, sslAlertThresholds);
                ps.setObject(9, notificationRetryCount);
                ps.setInt(10, version);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        systemSettingId = rs.getLong(1);

                        try (PreparedStatement hist = conn.prepareStatement("""
                            INSERT INTO system_settings_history
                            (system_setting_id, key, value, description, uptime_check_interval, uptime_retry_count,
                             uptime_retry_delay, ssl_check_interval, ssl_alert_thresholds, notification_retry_count,
                             version, action, changed_by, ip_address)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                            hist.setLong(1, systemSettingId);
                            hist.setString(2, key);
                            hist.setString(3, value);
                            hist.setString(4, description);
                            hist.setObject(5, uptimeCheckInterval);
                            hist.setObject(6, uptimeRetryCount);
                            hist.setObject(7, uptimeRetryDelay);
                            hist.setObject(8, sslCheckInterval);
                            hist.setString(9, sslAlertThresholds);
                            hist.setObject(10, notificationRetryCount);
                            hist.setInt(11, version);
                            hist.setString(12, action);
                            hist.setLong(13, ctx.getUserId());
                            hist.setString(14, ip);
                            hist.executeUpdate();
                        }

                        AuditLogger.log(exchange, "system_settings", systemSettingId, action, before.toString(), body.toString());

                        ResponseUtil.sendSuccess(exchange, "System setting upserted successfully",
                                Map.of("system_setting_id", systemSettingId, "version", version));
                        logger.info("System setting '{}' upserted with ID {} and version {}", key, systemSettingId, version);
                    } else {
                        ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to upsert system setting");
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to upsert system setting '{}': {}", key, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
