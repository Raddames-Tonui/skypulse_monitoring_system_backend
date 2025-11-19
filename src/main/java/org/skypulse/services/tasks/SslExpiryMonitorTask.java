package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;
import org.skypulse.utils.SslUtils;

import java.sql.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * SSL expiry monitor using monitored_services + ssl_logs + system_health
 */
public class SslExpiryMonitorTask implements ScheduledTask {

    @Override
    public String name() { return "SslExpiryMonitorTask"; }

    @Override
    public long intervalSeconds() { return 60 * 60 * 6; } // every 6 hours

    @Override
    public void execute() {
        System.out.println("[SSLMonitor] started");
        String selectServices = "SELECT monitored_service_id, monitored_service_name, monitored_service_url FROM monitored_services WHERE ssl_enabled = TRUE AND is_active = TRUE";

        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(selectServices);
             ResultSet rs = ps.executeQuery()) {

            c.setAutoCommit(false);

            while (rs.next()) {
                long serviceId = rs.getLong("monitored_service_id");
                String name = rs.getString("monitored_service_name");
                String url = rs.getString("monitored_service_url");

                try {
                    String host = extractHost(url);
                    int port = 443; // default port

                    ZonedDateTime expiry = SslUtils.getCertExpiry(host, port);
                    if (expiry != null) {
                        writeSslLog(c, serviceId, host, expiry);
                        writeSystemHealth(c, host, expiry);

                        long daysLeft = Duration.between(ZonedDateTime.now(), expiry).toDays();
                        if (daysLeft <= 7) {
                            insertEmailReminder(c, host, daysLeft);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[SSLMonitor] Failed for " + name + ": " + e.getMessage());
                }
            }

            c.commit();
        } catch (Exception ex) {
            System.err.println("[SSLMonitor] Error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static String extractHost(String url) {
        if (url == null) return null;
        try {
            String u = url.replaceFirst("^https?://", "");
            int slash = u.indexOf('/');
            return (slash > 0) ? u.substring(0, slash) : u;
        } catch (Exception e) {
            return url;
        }
    }

    private void writeSslLog(Connection c, long serviceId, String host, ZonedDateTime expiry) throws SQLException {
        long daysRemaining = Duration.between(ZonedDateTime.now(), expiry).toDays();

        String insert = "INSERT INTO ssl_logs(service_id, domain, issuer, expiry_date, days_remaining, last_checked, date_created, date_modified) " +
                "VALUES (?, ?, ?, ?, ?, now(), now(), now()) " +
                "ON CONFLICT (service_id, domain) DO UPDATE SET expiry_date = EXCLUDED.expiry_date, days_remaining = EXCLUDED.days_remaining, last_checked = now(), date_modified = now()";
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setLong(1, serviceId);
            ps.setString(2, host);
            ps.setString(3, ""); // issuer unknown for now
            ps.setDate(4, java.sql.Date.valueOf(expiry.toLocalDate()));
            ps.setLong(5, daysRemaining);
            ps.executeUpdate();
        }
    }

    private void writeSystemHealth(Connection c, String host, ZonedDateTime expiry) throws SQLException {
        String key = "ssl::" + host;
        String json = String.format("{\"expires\":\"%s\",\"host\":\"%s\",\"checked_at\":\"%s\"}",
                expiry.toString(), host, OffsetDateTime.now().toString());
        String upsert = "INSERT INTO system_health(key, value, updated_at) VALUES (?, to_jsonb(?::text)::jsonb, now()) " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()";
        try (PreparedStatement ps = c.prepareStatement(upsert)) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    private void insertEmailReminder(Connection c, String host, long daysLeft) throws SQLException {
        String subject = "Certificate expiring soon: " + host;
        String body = "The SSL certificate for " + host + " expires in " + daysLeft + " days.";

        String insert = "INSERT INTO email_log(user_id, email, subject, status, error_message, created_at) VALUES (?, ?, ?, ?, ?, now())";
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setNull(1, java.sql.Types.BIGINT); // system reminder, no specific user
            ps.setString(2, host + "@example.com"); // placeholder
            ps.setString(3, subject);
            ps.setString(4, "QUEUED");
            ps.setString(5, body);
            ps.executeUpdate();
        }
    }
}
