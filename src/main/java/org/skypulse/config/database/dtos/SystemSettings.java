package org.skypulse.config.database.dtos;

import org.skypulse.config.database.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SystemSettings {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettings.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static volatile SystemDefaults cachedDefaults;
    private static volatile List<ServiceConfig> cachedServices;

    private SystemSettings() {}

    public record SystemDefaults(
            long systemSettingId,
            String key,
            String description,
            int uptimeCheckInterval,
            int uptimeRetryCount,
            int uptimeRetryDelay,
            int ssePushInterval,
            int sslCheckInterval,
            List<Integer> sslAlertThresholds,
            int sslRetryCount,
            int sslRetryDelay,
            int notificationCheckInterval,
            int notificationRetryCount,
            int notificationCooldownMinutes,
            int version,
            boolean isActive,
            Long changedBy,
            Timestamp dateCreated,
            Timestamp dateModified
    ) {}

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

    public static SystemDefaults loadSystemDefaultsFromDB(Connection conn) throws Exception {
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
                        rs.getString("description"),
                        rs.getInt("uptime_check_interval"),
                        rs.getInt("uptime_retry_count"),
                        rs.getInt("uptime_retry_delay"),
                        rs.getInt("sse_push_interval"),
                        rs.getInt("ssl_check_interval"),
                        thresholds,
                        rs.getInt("ssl_retry_count"),
                        rs.getInt("ssl_retry_delay"),
                        rs.getInt("notification_check_interval"),
                        rs.getInt("notification_retry_count"),
                        rs.getInt("notification_cooldown_minutes"),
                        rs.getInt("version"),
                        rs.getBoolean("is_active"),
                        rs.getObject("changed_by") != null ? rs.getLong("changed_by") : null,
                        rs.getTimestamp("date_created"),
                        rs.getTimestamp("date_modified")
                );
            }
        }

        return new SystemDefaults(
                0L,
                "default",
                "Fallback defaults",
                5,
                3,
                5,
                60,
                360,
                Arrays.asList(30, 14, 7, 3),
                3,
                360,
                300,
                3,
                10,
                1,
                true,
                null,
                new Timestamp(System.currentTimeMillis()),
                new Timestamp(System.currentTimeMillis())
        );
    }

    public static List<ServiceConfig> loadActiveServicesFromDB(Connection conn) throws Exception {
        List<ServiceConfig> services = new ArrayList<>();
        String sql = "SELECT * FROM monitored_services WHERE is_active = TRUE";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                services.add(new ServiceConfig(
                        rs.getLong("monitored_service_id"),
                        rs.getObject("uuid", UUID.class),
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

    public static SystemDefaults loadSystemDefaults() throws Exception {
        if (cachedDefaults == null) refreshCache();
        return cachedDefaults;
    }

    public static List<ServiceConfig> loadActiveServices() throws Exception {
        if (cachedServices == null) refreshCache();
        return cachedServices;
    }

    public static Map<String, Object> loadAll() throws Exception {
        if (cachedDefaults == null || cachedServices == null) refreshCache();
        Map<String, Object> result = new HashMap<>();
        result.put("systemDefaults", cachedDefaults);
        result.put("services", cachedServices);
        return result;
    }

    private static synchronized void refreshCache() {
        try (Connection conn = JdbcUtils.getConnection()) {
            cachedDefaults = loadSystemDefaultsFromDB(conn);
            cachedServices = loadActiveServicesFromDB(conn);
            logger.info("SystemSettings cache refreshed successfully");
        } catch (Exception e) {
            logger.error("Failed to refresh SystemSettings cache", e);
        }
    }

    public static void startScheduledRefresh(long refreshIntervalMinutes) {
        refreshCache();
        scheduler.scheduleAtFixedRate(SystemSettings::refreshCache,
                refreshIntervalMinutes,
                refreshIntervalMinutes,
                TimeUnit.MINUTES);
    }
}
