package org.skypulse.config.database.dtos;

import org.skypulse.config.database.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/**
 * Loads system-wide default Settings and active monitored services
 *
 */
public final class SystemSettings {

    private SystemSettings() {}

    // Holds system-wide defaults
    public record SystemDefaults(
            long systemSettingId,
            String key,
            String value,
            String description,
            int uptimeCheckInterval,
            int uptimeRetryCount,
            int uptimeRetryDelay,
            int sslCheckInterval,
            List<Integer> sslAlertThresholds,
            int notificationCheckInterval,
            int notificationRetryCount,
            int notificationCooldownMinutes,
            int sslRetryCount,
            int sslRetryDelay,
            int version,
            boolean isActive,
            Timestamp dateCreated,
            Timestamp dateModified
    ) {}

    // Per-service configuration
    public record ServiceConfig(
            long serviceId,
            UUID uuid,
            String serviceName,
            String serviceUrl,
            String serviceRegion,
            int checkInterval,
            int retryCount,
            int retryDelay,
            int expectedStatusCode,
            boolean sslEnabled,
            String lastUptimeStatus,
            int consecutiveFailures,
            Long createdBy,
            Timestamp lastChecked,
            Timestamp dateCreated,
            Timestamp dateModified,
            boolean isActive
    ) {}

    // Fetch system-wide default Settings from DB
    public static SystemDefaults loadSystemDefaults(Connection conn) throws Exception {
        String sql = "SELECT * FROM system_settings LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                String thresholdsStr = rs.getString("ssl_alert_thresholds");
                List<Integer> thresholds = new ArrayList<>();
                if (thresholdsStr != null && !thresholdsStr.isBlank()) {
                    for (String s : thresholdsStr.split(",")) {
                        try { thresholds.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                    }
                }
                thresholds.sort(Collections.reverseOrder());

                return new SystemDefaults(
                        rs.getLong("system_setting_id"),
                        rs.getString("key"),
                        rs.getString("value"),
                        rs.getString("description"),
                        rs.getInt("uptime_check_interval"),
                        rs.getInt("uptime_retry_count"),
                        rs.getInt("uptime_retry_delay"),
                        rs.getInt("ssl_check_interval"),
                        thresholds,
                        rs.getInt("ssl_retry_count"),
                        rs.getInt("ssl_retry_delay"),
                        rs.getInt("notification_check_interval"),
                        rs.getInt("notification_retry_count"),
                        rs.getInt("notification_cooldown_minutes"),
                        rs.getInt("version"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("date_created"),
                        rs.getTimestamp("date_modified")
                );
            }
        }

        // fallback defaults
        return new SystemDefaults(
                0L,
                "default",
                null,
                "Fallback defaults",
                5,
                3,
                5,
                360,
                Arrays.asList(30, 14, 7, 3),
                5,
                3,
                10,
                3,
                300,
                1,
                true,
                new Timestamp(System.currentTimeMillis()),
                new Timestamp(System.currentTimeMillis())
        );
    }

    // Load all active monitored services
    public static List<ServiceConfig> loadActiveServices(Connection conn) throws Exception {
        List<ServiceConfig> services = new ArrayList<>();
        String sql = "SELECT * FROM monitored_services WHERE is_active = TRUE";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                services.add(new ServiceConfig(
                        rs.getLong("monitored_service_id"),
                        (UUID) rs.getObject("uuid"),
                        rs.getString("monitored_service_name"),
                        rs.getString("monitored_service_url"),
                        rs.getString("monitored_service_region"),
                        rs.getInt("check_interval"),
                        rs.getInt("retry_count"),
                        rs.getInt("retry_delay"),
                        rs.getInt("expected_status_code"),
                        rs.getBoolean("ssl_enabled"),
                        rs.getString("last_uptime_status"),
                        rs.getInt("consecutive_failures"),
                        rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                        rs.getTimestamp("last_checked"),
                        rs.getTimestamp("date_created"),
                        rs.getTimestamp("date_modified"),
                        rs.getBoolean("is_active")
                ));
            }
        }

        return services;
    }

    // Load System settings and service settings
    public static Map<String, Object> loadAll() throws Exception {
        try (Connection conn = JdbcUtils.getConnection()) {
            SystemDefaults defaults = loadSystemDefaults(conn);
            List<ServiceConfig> services = loadActiveServices(conn);

            Map<String, Object> result = new HashMap<>();
            result.put("systemDefaults", defaults);
            result.put("services", services);
            return result;
        }
    }
}
