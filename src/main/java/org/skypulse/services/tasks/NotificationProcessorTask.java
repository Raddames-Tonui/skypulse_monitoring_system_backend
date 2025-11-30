package org.skypulse.services.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.dtos.SystemSettings.SystemDefaults;
import org.skypulse.notifications.NotificationSender;
import org.skypulse.notifications.RecipientResolver;
import org.skypulse.notifications.TemplateLoader;
import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class NotificationProcessorTask implements ScheduledTask {

    private static final Logger logger = LoggerFactory.getLogger(NotificationProcessorTask.class);

    private final NotificationSender sender;
    private final SystemDefaults systemDefaults;
    private final TemplateLoader templateLoader = new TemplateLoader();
    private final ScheduledExecutorService executor;

    private static final ObjectMapper mapper = JsonUtil.mapper();

    public NotificationProcessorTask(NotificationSender sender,
                                     SystemDefaults systemDefaults,
                                     int workerThreads) {
        this.sender = sender;
        this.systemDefaults = systemDefaults;
        this.executor = Executors.newScheduledThreadPool(workerThreads);
    }

    @Override
    public String name() { return "[ NotificationProcessorTask ]"; }

    @Override
    public long intervalSeconds() { return Math.max(systemDefaults.notificationCheckInterval(), 3); }

    @Override
    public void execute() {
        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);

            int retryCount = systemDefaults.notificationRetryCount();
            int cooldownMinutes = systemDefaults.notificationCooldownMinutes();
            int retryDelaySeconds = systemDefaults.uptimeRetryDelay();

            String selectSql = """
                SELECT event_outbox_id, service_id, event_type, payload, first_failure_at
                FROM event_outbox
                WHERE status = 'PENDING'
                FOR UPDATE SKIP LOCKED
                LIMIT 50
                """;

            try (PreparedStatement ps = conn.prepareStatement(selectSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    long eventId = rs.getLong("event_outbox_id");
                    long serviceId = rs.getLong("service_id");
                    String eventType = rs.getString("event_type");
                    String payloadJson = rs.getString("payload");
                    Timestamp firstFailureAt = rs.getTimestamp("first_failure_at");

                    try {
                        JsonNode node = mapper.readTree(payloadJson);
                        List<Map<String,Object>> payloads = new ArrayList<>();

                        if (node.isArray()) {
                            for (JsonNode item : node) {
                                if (item.isObject()) {
                                    payloads.add(mapper.convertValue(item,
                                            new com.fasterxml.jackson.core.type.TypeReference<>() {}));
                                }
                            }
                        } else if (node.isObject()) {
                            payloads.add(mapper.convertValue(node,
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {}));
                        }

                        for (Map<String,Object> payload : payloads) {
                            scheduleSendTask(eventId, serviceId, eventType, payload, firstFailureAt,
                                    retryCount, retryDelaySeconds, cooldownMinutes);
                        }

                        markProcessed(conn, eventId);

                    } catch (Exception e) {
                        logger.error("Failed processing event {}: {}", eventId, e.getMessage(), e);
                        markFailed(conn, eventId, e.getMessage());
                    }
                }

                conn.commit();

            } catch (Exception e) {
                conn.rollback();
                logger.error("Batch failure: {}", e.getMessage(), e);
            }

        } catch (Exception e) {
            logger.error("Database error: {}", e.getMessage(), e);
        }
    }

    private void scheduleSendTask(long eventId, long serviceId, String eventType, Map<String,Object> payload,
                                  Timestamp firstFailureAt, int retryCount, int retryDelaySeconds, int cooldownMinutes) {
        Runnable task = () -> {
            try {
                sendNotifications(eventId, serviceId, eventType, payload, firstFailureAt,
                        retryCount, retryDelaySeconds, cooldownMinutes);
            } catch (Exception e) {
                logger.error("Error sending notifications for event {}: {}", eventId, e.getMessage(), e);
            }
        };
        executor.submit(task);
    }

    private void sendNotifications(long eventId, long serviceId, String eventType, Map<String,Object> payload,
                                   Timestamp firstFailureAt, int retryCount, int retryDelaySeconds, int cooldownMinutes) throws Exception {

        //  cooldown
        boolean allowedToSend = true;
        if ("SERVICE_DOWN".equals(eventType) && firstFailureAt != null) {
            Instant expiry = firstFailureAt.toInstant().plus(Duration.ofMinutes(cooldownMinutes));
            if (expiry.isAfter(Instant.now())) allowedToSend = false;
        }
        if (!allowedToSend) return;

        if ("SERVICE_RECOVERED".equals(eventType) && firstFailureAt != null) {
            Duration dt = Duration.between(firstFailureAt.toInstant(), Instant.now());
            payload.put("downtime_seconds", dt.getSeconds());
        }

        //  Load templates
        String subjectTpl, bodyTpl;
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT subject_template, body_template, body_template_key, storage_mode
                     FROM notification_templates
                     WHERE event_type = ?
                 """)) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new Exception("Missing template for event=" + eventType);

                subjectTpl = rs.getString("subject_template");
                bodyTpl = templateLoader.load(rs.getString("storage_mode"),
                        rs.getString("body_template"), rs.getString("body_template_key"));
            }
        }

        String subject = renderTemplate(subjectTpl, payload);
        String body = renderTemplate(bodyTpl, payload);

        //  Fetch recipients
        List<RecipientResolver.Recipient> recipients;
        try (Connection conn = JdbcUtils.getConnection()) {
            recipients = RecipientResolver.resolveRecipients(conn, eventType, serviceId, payload.get("userId"));
        }

        //  Send each recipient with retries
        for (RecipientResolver.Recipient r : recipients) {
            boolean sent = false;
            Exception lastEx = null;
            for (int attempt = 1; attempt <= retryCount && !sent; attempt++) {
                try {
                    sent = sender.send(r.type(), r.value(), subject, body, Map.of());
                } catch (Exception e) {
                    lastEx = e;
                }
                if (!sent) Thread.sleep(retryDelaySeconds * 1000L);
            }

            //  Write history
            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement h = conn.prepareStatement("""
                         INSERT INTO notification_history (
                             service_id, contact_group_id, contact_group_member_id,
                             notification_channel_id, recipient, subject, message,
                             status, sent_at, error_message,
                             include_pdf, pdf_template_id, pdf_file_path,
                             pdf_file_hash, pdf_generated_at
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?)
                     """)) {

                h.setLong(1, serviceId);
                h.setNull(2, Types.BIGINT); // contact_group_id
                h.setNull(3, Types.BIGINT); // contact_group_member_id

                Long channelId = getEmailChannelId();
                if (channelId != null) {
                    h.setLong(4, channelId);
                } else {
                    h.setNull(4, Types.BIGINT);
                }

                h.setString(5, r.value());
                h.setString(6, subject);
                h.setString(7, body);
                h.setString(8, sent ? "SENT" : "FAILED");
                h.setString(9, sent ? null : (lastEx != null ? lastEx.getMessage() : "Retries exhausted"));

                h.setNull(10, Types.BOOLEAN);
                h.setNull(11, Types.BIGINT);
                h.setNull(12, Types.VARCHAR);
                h.setNull(13, Types.VARCHAR);
                h.setNull(14, Types.TIMESTAMP);

                h.executeUpdate();
            }
        }
    }

    private Long getEmailChannelId() {
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT notification_channel_id
                     FROM notification_channels
                     WHERE notification_channel_code = 'EMAIL' AND is_enabled = TRUE
                     LIMIT 1
                 """)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("notification_channel_id");
            }
        } catch (SQLException e) {
            logger.error("Failed fetching EMAIL channel_id: {}", e.getMessage(), e);
        }
        return null;
    }

    private String renderTemplate(String tpl, Map<String,Object> payload) throws Exception {
        Mustache m = new DefaultMustacheFactory().compile(new StringReader(tpl), "tpl");
        StringWriter w = new StringWriter();
        m.execute(w, payload).flush();
        return w.toString();
    }

    private void markProcessed(Connection conn, long eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='PROCESSED', updated_at=NOW() WHERE event_outbox_id=?"
        )) { ps.setLong(1, eventId); ps.executeUpdate(); }
    }

    private void markFailed(Connection conn, long eventId, String reason) throws SQLException, JsonProcessingException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='FAILED', updated_at=NOW(), payload = payload || ?::jsonb WHERE event_outbox_id=?"
        )) {
            ps.setString(1, JsonUtil.mapper().writeValueAsString(Map.of("error", reason)));
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }
}
