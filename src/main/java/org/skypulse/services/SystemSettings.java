package org.skypulse.services;

import org.skypulse.config.database.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/**
 * SystemSettings loads system-wide defaults and active monitored services
 */
public final class SystemSettings {

    private SystemSettings() {}

    // Holds system-wide defaults
    public static class SystemDefaults {
        public final int uptimeCheckInterval;
        public final int uptimeRetryCount;
        public final int uptimeRetryDelay;
        public final int sslCheckInterval;
        public final List<Integer> sslAlertThresholds;
        public final long systemSettingId;
        public final String key;
        public final String value;
        public final String description;
        public final Timestamp dateCreated;
        public final Timestamp dateModified;

        public SystemDefaults(long systemSettingId, int uptimeCheckInterval, int uptimeRetryCount, int uptimeRetryDelay,
                              int sslCheckInterval, List<Integer> sslAlertThresholds,
                              String key, String value, String description, Timestamp dateCreated, Timestamp dateModified) {
            this.systemSettingId = systemSettingId;
            this.uptimeCheckInterval = uptimeCheckInterval;
            this.uptimeRetryCount = uptimeRetryCount;
            this.uptimeRetryDelay = uptimeRetryDelay;
            this.sslCheckInterval = sslCheckInterval;
            this.sslAlertThresholds = sslAlertThresholds;
            this.key = key;
            this.value = value;
            this.description = description;
            this.dateCreated = dateCreated;
            this.dateModified = dateModified;
        }
    }

    //  per-service configuration
    public static class ServiceConfig {
        public final long serviceId;
        public final UUID uuid;
        public final String serviceName;
        public final String serviceUrl;
        public final String serviceRegion;
        public final int checkInterval;
        public final int retryCount;
        public final int retryDelay;
        public final int expectedStatusCode;
        public final boolean sslEnabled;
        public final String lastUptimeStatus;
        public final int consecutiveFailures;
        public final Long createdBy;
        public final Timestamp dateCreated;
        public final Timestamp dateModified;
        public final boolean isActive;

        public ServiceConfig(long serviceId, UUID uuid, String serviceName, String serviceUrl,
                             String serviceRegion, int checkInterval, int retryCount, int retryDelay,
                             int expectedStatusCode, boolean sslEnabled, String lastUptimeStatus,
                             int consecutiveFailures, Long createdBy, Timestamp dateCreated, Timestamp dateModified,
                             boolean isActive) {
            this.serviceId = serviceId;
            this.uuid = uuid;
            this.serviceName = serviceName;
            this.serviceUrl = serviceUrl;
            this.serviceRegion = serviceRegion;
            this.checkInterval = checkInterval;
            this.retryCount = retryCount;
            this.retryDelay = retryDelay;
            this.expectedStatusCode = expectedStatusCode;
            this.sslEnabled = sslEnabled;
            this.lastUptimeStatus = lastUptimeStatus;
            this.consecutiveFailures = consecutiveFailures;
            this.createdBy = createdBy;
            this.dateCreated = dateCreated;
            this.dateModified = dateModified;
            this.isActive = isActive;
        }
    }

    // Fetch system-wide defaults from DB
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
                        rs.getInt("uptime_check_interval"),
                        rs.getInt("uptime_retry_count"),
                        rs.getInt("uptime_retry_delay"),
                        rs.getInt("ssl_check_interval"),
                        thresholds,
                        rs.getString("key"),
                        rs.getString("value"),
                        rs.getString("description"),
                        rs.getTimestamp("date_created"),
                        rs.getTimestamp("date_modified")
                );
            }
        }

        // fallback defaults
        return new SystemDefaults(
                0L, 5, 3, 5, 360, Arrays.asList(30, 14, 7, 3),
                "default", null, "Fallback defaults", new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis())
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
