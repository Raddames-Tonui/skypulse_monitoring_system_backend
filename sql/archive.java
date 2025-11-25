package org.skypulse.services.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.skypulse.services.ScheduledTask;
import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
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
import java.util.concurrent.*;
import java.security.cert.X509Certificate;

public class SslExpiryMonitorTask implements ScheduledTask {

    private static final Logger logger = LoggerFactory.getLogger(SslExpiryMonitorTask.class);
    private static final ObjectMapper mapper = JsonUtil.mapper();

    private static final int THREADS = 5;

    private final long sslCheckIntervalSeconds;
    private final List<Integer> alertThresholds;

    private final SystemSettings.SystemDefaults defaults;


    public SslExpiryMonitorTask(SystemSettings.SystemDefaults defaults) {
        this.defaults = defaults;

        // Interval: system → fallback → minimum
        int interval = defaults.sslCheckInterval();
        if (interval <= 0) interval = 60 * 60 * 6;     // fallback 6 hours
        this.sslCheckIntervalSeconds = interval;

        // Alert thresholds with fallback
        List<Integer> th = defaults.sslAlertThresholds();
        this.alertThresholds = (th != null && !th.isEmpty())
                ? th
                : List.of(30, 14, 7, 3);

        logger.info("[------- SSL Monitor initialized: interval={} minutes thresholds={} ----------]",
                sslCheckIntervalSeconds / 60.0,
                alertThresholds);

    }

    @Override
    public String name() {
        return "[ SslExpiryMonitorTask ]";
    }

    @Override
    public long intervalSeconds() {
        return sslCheckIntervalSeconds;
    }

    @Override
    public void execute() {
        logger.info("Starting SslExpiryMonitorTask");

        List<Map<String, Object>> services = new ArrayList<>();

        try (Connection c = JdbcUtils.getConnection()) {
            String sql =
                    "SELECT monitored_service_id, monitored_service_name, monitored_service_url " +
                            "FROM monitored_services WHERE ssl_enabled = TRUE AND is_active = TRUE";

            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> svc = new HashMap<>();
                    svc.put("id", rs.getLong("monitored_service_id"));
                    svc.put("name", rs.getString("monitored_service_name"));
                    svc.put("url", rs.getString("monitored_service_url"));
                    services.add(svc);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to load services: {}", e.getMessage(), e);
            return;
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            try {
                List<Future<?>> futures = new ArrayList<>();

                for (Map<String, Object> svc : services) {
                    futures.add(executor.submit(() -> checkService(
                            (Long) svc.get("id"),
                            (String) svc.get("name"),
                            (String) svc.get("url")
                    )));
                }

                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        logger.error("SSL check task failed: {}", e.getMessage(), e);
                    }
                }
            } finally {
                executor.shutdown();
            }
        }
        logger.info("SslExpiryMonitorTask completed");
    }

    // PER-SERVICE CHECK
    private void checkService(long serviceId, String serviceName, String url) {
        String host = extractHost(url);
        if (host == null || host.isBlank()) {
            logger.warn("Invalid host for service {}: {}", serviceName, url);
            return;
        }

        try (Connection c = JdbcUtils.getConnection()) {
            c.setAutoCommit(false);

            X509Certificate cert = SslUtils.getServerCert(host, 443);
            if (cert == null) {
                logger.warn("No certificate found for host {}", host);
                upsertSslLogFailure(c, serviceId, host);
                return;
            }

            Map<String, Object> certInfo = SslUtils.extractCertInfo(cert);

            ZonedDateTime expiry = ZonedDateTime.ofInstant(
                    cert.getNotAfter().toInstant(),
                    ZonedDateTime.now().getZone()
            );

            int daysLeft = (int) Duration.between(ZonedDateTime.now(), expiry).toDays();

            upsertSslLog(c, serviceId, host, certInfo, expiry, daysLeft);
            checkAndCreateAlerts(c, serviceId, serviceName, host, certInfo, daysLeft);

            c.commit();

        } catch (Exception e) {
            logger.error("SSL check failed for service ({}) {} [{}] : {}",
                    serviceId, serviceName, url, e.getMessage(), e);
        }
    }

    private void upsertSslLog(Connection c, long serviceId, String host, Map<String, Object> certInfo,
                              ZonedDateTime expiry, int daysRemaining) throws SQLException {

        String update = """
                UPDATE ssl_logs SET
                    issuer = ?, serial_number = ?, signature_algorithm = ?, public_key_algo = ?, public_key_length = ?,
                    san_list = ?, chain_valid = ?, subject = ?, fingerprint = ?, issued_date = ?, expiry_date = ?,
                    days_remaining = ?, last_checked = now(), date_modified = now()
                WHERE monitored_service_id = ? AND domain = ?
                """;

        try (PreparedStatement ps = c.prepareStatement(update)) {
            ps.setString(1, (String) certInfo.get("issuer"));
            ps.setString(2, (String) certInfo.get("serial_number"));
            ps.setString(3, (String) certInfo.get("sig_algo"));
            ps.setString(4, (String) certInfo.get("pub_algo"));
            ps.setInt(5, (Integer) certInfo.get("pub_len"));
            ps.setString(6, (String) certInfo.get("sans"));
            ps.setBoolean(7, (Boolean) certInfo.get("chain_valid"));
            ps.setString(8, (String) certInfo.get("subject"));
            ps.setString(9, (String) certInfo.get("fingerprint"));
            ps.setDate(10, new Date(((java.util.Date) certInfo.get("issued_date")).getTime()));
            ps.setDate(11, new Date(((java.util.Date) certInfo.get("expiry_date")).getTime()));
            ps.setInt(12, daysRemaining);
            ps.setLong(13, serviceId);
            ps.setString(14, host);

            if (ps.executeUpdate() > 0) return;
        }

        String insert = """
                INSERT INTO ssl_logs(
                    monitored_service_id, domain, issuer, serial_number, signature_algorithm,
                    public_key_algo, public_key_length, san_list, chain_valid,
                    subject, fingerprint, issued_date, expiry_date, days_remaining,
                    last_checked, date_created, date_modified
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), now()
                )
                """;

        try (PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setLong(1, serviceId);
            ps.setString(2, host);
            ps.setString(3, (String) certInfo.get("issuer"));
            ps.setString(4, (String) certInfo.get("serial_number"));
            ps.setString(5, (String) certInfo.get("sig_algo"));
            ps.setString(6, (String) certInfo.get("pub_algo"));
            ps.setInt(7, (Integer) certInfo.get("pub_len"));
            ps.setString(8, (String) certInfo.get("sans"));
            ps.setBoolean(9, (Boolean) certInfo.get("chain_valid"));
            ps.setString(10, (String) certInfo.get("subject"));
            ps.setString(11, (String) certInfo.get("fingerprint"));
            ps.setDate(12, new Date(((java.util.Date) certInfo.get("issued_date")).getTime()));
            ps.setDate(13, new Date(((java.util.Date) certInfo.get("expiry_date")).getTime()));
            ps.setInt(14, daysRemaining);
            ps.executeUpdate();
        }
    }

    private void checkAndCreateAlerts(Connection c, long serviceId, String serviceName, String host,
                                      Map<String, Object> certInfo, int daysLeft) throws SQLException {

        for (int threshold : alertThresholds) {
            if (daysLeft <= threshold && !alertAlreadySent(c, serviceId, threshold)) {
                insertAlertRecord(c, serviceId, threshold);
                createSslExpiryEvent(
                        c, serviceId, serviceName, host,
                        (String) certInfo.get("issuer"),
                        daysLeft, threshold
                );
            }
        }
    }

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
        String sql =
                "INSERT INTO ssl_alerts(monitored_service_id, days_remaining, sent_at) " +
                        "VALUES (?, ?, now()) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setInt(2, threshold);
            ps.executeUpdate();
        }
    }

    private void createSslExpiryEvent(Connection c, long serviceId, String serviceName,
                                      String host, String issuer, int daysLeft, int threshold) {

        String eventType = "SSL_EXPIRING";

        Map<String, Object> payload = new HashMap<>();
        payload.put("service_id", serviceId);
        payload.put("service_name", serviceName);
        payload.put("domain", host);
        payload.put("issuer", issuer);
        payload.put("days_remaining", daysLeft);
        payload.put("threshold", threshold);
        payload.put("checked_at", OffsetDateTime.now().toString());

        String sql =
                "INSERT INTO event_outbox(event_type, payload, status, retries, created_at, updated_at) " +
                        "VALUES (?, ?::jsonb, 'PENDING', 0, now(), now())";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, mapper.writeValueAsString(payload));
            ps.executeUpdate();

            logger.info(
                    "SSL alert event created for service {} | threshold {} | daysLeft {}",
                    serviceName, threshold, daysLeft
            );

        } catch (Exception e) {
            logger.error("Failed to create SSL expiry event: {}", e.getMessage(), e);
        }
    }

    private String extractHost(String url) {
        try {
            String cleaned = url.replaceFirst("^https?://", "");
            int slash = cleaned.indexOf('/');
            String host = (slash >= 0) ? cleaned.substring(0, slash) : cleaned;

            int at = host.lastIndexOf('@');
            if (at >= 0) host = host.substring(at + 1);

            int colon = host.indexOf(':');
            return (colon >= 0) ? host.substring(0, colon) : host;

        } catch (Exception e) {
            return url;
        }
    }

    private void upsertSslLogFailure (Connection conn,  long serviceId, String host) throws SQLException {
        String update = """
                UPDATE ssl_logs SET last_checked = now(), days_remaining = -1, date_modified = now()
                WHERE monitored_service_id = ? AND domain = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, serviceId);
            ps.setString(2, host);
            if (ps.executeUpdate() >  0) return;
        }
        String insert = """
                INSERT INTO ssl_logs(monitored_service_id, domain, days_remaining, last_checked, date_created, date_modified)
                VALUES (?, ?, -1, now(), now(), now())
                """;

        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setLong(1, serviceId);
            ps.setString(2, host);
            ps.executeUpdate();
        }
    }
}
