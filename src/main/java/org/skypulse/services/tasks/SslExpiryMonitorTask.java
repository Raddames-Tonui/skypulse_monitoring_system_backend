package org.skypulse.services.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;
import org.skypulse.utils.SslUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * SSL expiry monitor using monitored_services + ssl_logs + ssl_alerts + event_outbox
 */
public class SslExpiryMonitorTask implements ScheduledTask {

    private static final Logger logger = LoggerFactory.getLogger(SslExpiryMonitorTask.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "[ SslExpiryMonitorTask ]";
    }

    @Override
    public long intervalSeconds() {
        return 60L * 60L * 6L; // every 6 hours
    }

    @Override
    public void execute() {
        try (Connection c = JdbcUtils.getConnection()) {
            c.setAutoCommit(false);

            List<Integer> thresholds = loadThresholds(c);
            logger.info("SSL alert thresholds = {}", thresholds);

            String sql = """
                SELECT monitored_service_id, monitored_service_name, monitored_service_url
                FROM monitored_services
                WHERE ssl_enabled = TRUE AND is_active = TRUE
            """;

            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    long serviceId = rs.getLong("monitored_service_id");
                    String serviceName = rs.getString("monitored_service_name");
                    String url = rs.getString("monitored_service_url");

                    try {
                        String host = extractHost(url);
                        if (host == null || host.isBlank()) {
                            logger.warn("Invalid host extracted from URL {}", url);
                            continue;
                        }

                        ZonedDateTime expiry = SslUtils.getCertExpiry(host, 443);
                        String issuer = tryGetIssuer(host);

                        if (expiry == null) {
                            logger.warn("No cert expiry for host {}", host);
                            continue;
                        }

                        int daysLeft = (int) Duration.between(ZonedDateTime.now(), expiry).toDays();

                        upsertSslLog(c, serviceId, host, issuer, expiry, daysLeft);

                        // check thresholds
                        for (int t : thresholds) {
                            if (daysLeft <= t && !alertAlreadySent(c, serviceId, t)) {
                                insertAlertRecord(c, serviceId, t);
                                createSslExpiryEvent(c, serviceId, serviceName, host, issuer, daysLeft, t);
                            }
                        }

                    } catch (Exception e) {
                        logger.error("SSL check failed for service [ {} ] ({}): {}", serviceName, serviceId, e.getMessage(), e);
                    }
                }
            }

            c.commit();
            logger.info("SslExpiryMonitorTask completed");

        } catch (Exception e) {
            logger.error("SslExpiryMonitorTask failed: {}", e.getMessage(), e);
        }
    }

    // Thresholds
    private List<Integer> loadThresholds(Connection c) {
        String sql = "SELECT value FROM system_settings WHERE key = 'ssl_alert_thresholds'";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                String val = rs.getString(1);
                if (val != null && !val.isBlank()) {
                    List<Integer> list = new ArrayList<>();
                    for (String p : val.split(",")) {
                        try {
                            list.add(Integer.parseInt(p.trim()));
                        } catch (Exception ignored) {}
                    }
                    list.sort(Collections.reverseOrder());
                    return list;
                }
            }
        } catch (SQLException ex) {
            logger.warn("Failed to load SSL thresholds ({})", ex.getMessage());
        }
        return Arrays.asList(30, 14, 7, 3);
    }

    private String extractHost(String url) {
        try {
            String u = url.replaceFirst("^https?://", "");
            int slash = u.indexOf('/');
            String host = (slash >= 0) ? u.substring(0, slash) : u;
            int at = host.lastIndexOf('@');
            if (at >= 0) host = host.substring(at + 1);
            int col = host.indexOf(':');
            return (col >= 0) ? host.substring(0, col) : host;
        } catch (Exception e) {
            return url;
        }
    }

    private String tryGetIssuer(String host) {
        try {
            return SslUtils.getCertIssuer(host, 443);
        } catch (Throwable ignored) {
            return "";
        }
    }

    // SSL Logs
    private void upsertSslLog(Connection c, long serviceId, String host, String issuer, ZonedDateTime expiry, int daysRemaining) throws SQLException {

        String update = """
            UPDATE ssl_logs SET
              issuer = ?, expiry_date = ?, days_remaining = ?, last_checked = now(), date_modified = now()
            WHERE monitored_service_id = ? AND domain = ?
        """;

        try (PreparedStatement ps = c.prepareStatement(update)) {
            ps.setString(1, issuer);
            ps.setDate(2, Date.valueOf(expiry.toLocalDate()));
            ps.setInt(3, daysRemaining);
            ps.setLong(4, serviceId);
            ps.setString(5, host);
            int updated = ps.executeUpdate();
            if (updated > 0) return;
        }

        String insert = """
            INSERT INTO ssl_logs(monitored_service_id, domain, issuer, expiry_date, days_remaining, last_checked, date_created, date_modified)
            VALUES (?, ?, ?, ?, ?, now(), now(), now())
        """;

        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setLong(1, serviceId);
            ps.setString(2, host);
            ps.setString(3, issuer);
            ps.setDate(4, Date.valueOf(expiry.toLocalDate()));
            ps.setInt(5, daysRemaining);
            ps.executeUpdate();
        }
    }

    // SSL Alerts
    private boolean alertAlreadySent(Connection c, long serviceId, int threshold) throws SQLException {
        String sql = "SELECT 1 FROM ssl_alerts WHERE monitored_service_id = ? AND days_remaining = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setInt(2, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertAlertRecord(Connection c, long serviceId, int threshold) throws SQLException {
        String sql = """
            INSERT INTO ssl_alerts(monitored_service_id, days_remaining, sent_at)
            VALUES (?, ?, now())
            ON CONFLICT DO NOTHING
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setInt(2, threshold);
            ps.executeUpdate();
        }
    }

    // Event Outbox
    private void createSslExpiryEvent(Connection c,
                                      long serviceId,
                                      String serviceName,
                                      String host,
                                      String issuer,
                                      int daysLeft,
                                      int threshold) {

        String eventType = "SSL_EXPIRING";
        Map<String, Object> payload = new HashMap<>();
        payload.put("service_id", serviceId);
        payload.put("service_name", serviceName);
        payload.put("domain", host);
        payload.put("issuer", issuer);
        payload.put("days_remaining", daysLeft);
        payload.put("threshold", threshold);
        payload.put("checked_at", OffsetDateTime.now().toString());

        String sql = """
            INSERT INTO event_outbox(event_type, payload, status, retries, created_at, updated_at)
            VALUES (?, ?::jsonb, 'PENDING', 0, NOW(), NOW())
        """;

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, MAPPER.writeValueAsString(payload));
            ps.executeUpdate();
            logger.info("SSL alert event created for service {} threshold {} daysLeft {}",
                    serviceName, threshold, daysLeft);
        } catch (Exception e) {
            logger.error("Failed creating SSL expiry event: {}", e.getMessage(), e);
        }
    }
}
