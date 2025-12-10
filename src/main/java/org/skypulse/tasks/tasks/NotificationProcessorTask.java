package org.skypulse.tasks.tasks;

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
import org.skypulse.tasks.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class NotificationProcessorTask implements ScheduledTask {

    private static final Logger logger = LoggerFactory.getLogger(NotificationProcessorTask.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final NotificationSender sender;
    private final SystemDefaults systemDefaults;
    private final TemplateLoader templateLoader = new TemplateLoader();
    private final ScheduledExecutorService executor;

    public NotificationProcessorTask(NotificationSender sender, SystemDefaults systemDefaults, int workerThreads) {
        this.sender = sender;
        this.systemDefaults = systemDefaults;
        this.executor = Executors.newScheduledThreadPool(workerThreads);
    }

    @Override
    public String name() {
        return "[ NotificationProcessorTask ]";
    }

    @Override
    public long intervalSeconds() {
        return Math.max(systemDefaults.notificationCheckInterval(), 3);
    }

    @Override
    public void execute() {
        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);

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
                        List<Map<String, Object>> payloads = parsePayload(payloadJson);

                        for (Map<String, Object> payload : payloads) {
                            scheduleSendTask(eventId, serviceId, eventType, payload, firstFailureAt,
                                    systemDefaults.notificationRetryCount(),
                                    systemDefaults.uptimeRetryDelay(),
                                    systemDefaults.notificationCooldownMinutes());
                        }

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

    private List<Map<String, Object>> parsePayload(String payloadJson) throws Exception {
        JsonNode node = mapper.readTree(payloadJson);
        List<Map<String, Object>> payloads = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject()) {
                    payloads.add(mapper.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
                }
            }
        } else if (node.isObject()) {
            payloads.add(mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        }
        return payloads;
    }

    private void scheduleSendTask(long eventId, long serviceId, String eventType, Map<String, Object> payload,
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

    private void sendNotifications(long eventId, long serviceId, String eventType, Map<String, Object> payload,
                                   Timestamp firstFailureAt, int retryCount, int retryDelaySeconds, int cooldownMinutes) throws Exception {

        // Skip cooldown for SERVICE_DOWN
        if ("SERVICE_DOWN".equalsIgnoreCase(eventType) && firstFailureAt != null) {
            Instant expiry = firstFailureAt.toInstant().plus(Duration.ofMinutes(cooldownMinutes));
            if (expiry.isAfter(Instant.now())) return;
        }

        // Add downtime for SERVICE_RECOVERED
        if ("SERVICE_RECOVERED".equalsIgnoreCase(eventType) && firstFailureAt != null) {
            Duration dt = Duration.between(firstFailureAt.toInstant(), Instant.now());
            payload.put("downtime_seconds", dt.getSeconds());
        }

        // Load all recipients
        List<RecipientResolver.Recipient> recipients;
        try (Connection conn = JdbcUtils.getConnection()) {
            recipients = RecipientResolver.resolveRecipients(conn, eventType, serviceId, payload.get("userId"));
        }

        // Group recipients by channel type
        Map<String, List<RecipientResolver.Recipient>> recipientsByType = new HashMap<>();
        for (RecipientResolver.Recipient r : recipients) {
            String type = r.type().toUpperCase();
            recipientsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<RecipientResolver.Recipient>> entry : recipientsByType.entrySet()) {
            String channel = entry.getKey();
            List<RecipientResolver.Recipient> channelRecipients = entry.getValue();

            // Fetch template for this channel
            String subjectTpl = null;
            String bodyTpl = null;
            String bodyTemplateKey = null;

            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                     SELECT subject_template, body_template, body_template_key
                     FROM notification_templates
                     WHERE event_type = ?
                 """)) {

                ps.setString(1, eventType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        subjectTpl = rs.getString("subject_template");
                        bodyTpl = rs.getString("body_template");
                        bodyTemplateKey = rs.getString("body_template_key");

                        // Load the proper template for this channel
                        bodyTpl = templateLoader.load(channel, bodyTpl, bodyTemplateKey);
                    }
                }
            }

            if (subjectTpl == null || bodyTpl == null) {
                logger.warn("No template found for event={} channel={}, skipping notifications", eventType, channel);
                continue;
            }

            // Render Mustache templates
            String subject = renderTemplate(subjectTpl, bodyTemplateKey, payload);
            String body = renderTemplate(bodyTpl, bodyTemplateKey, payload);

            // Inline images only for EMAIL
            Map<String, String> inlineImages = Map.of();
            if ("EMAIL".equalsIgnoreCase(channel)) {
                inlineImages = Map.of(
                        "skypulseLogo", new File(
                                Objects.requireNonNull(getClass().getClassLoader().getResource("images/skypulse_logo.png")).toURI()
                        ).getAbsolutePath()
                );
            }

            // Send notifications
            for (RecipientResolver.Recipient r : channelRecipients) {
                boolean sent = false;
                Exception lastEx = null;

                for (int attempt = 1; attempt <= retryCount && !sent; attempt++) {
                    try {
                        sent = sender.send(channel, r.value(), subject, body, inlineImages);
                    } catch (Exception e) {
                        lastEx = e;
                    }
                    if (!sent) Thread.sleep(retryDelaySeconds * 1000L);
                }

                // Log history
                try (Connection conn = JdbcUtils.getConnection();
                     PreparedStatement h = conn.prepareStatement("""
                         INSERT INTO notification_history (
                             service_id,
                             contact_group_id,
                             user_id,
                             notification_channel_id,
                             recipient,
                             subject,
                             message,
                             status,
                             sent_at,
                             error_message
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)
                     """)) {

                    h.setLong(1, serviceId);
                    if (r.contactGroupId() != null && r.contactGroupId() > 0) h.setLong(2, r.contactGroupId());
                    else h.setNull(2, Types.BIGINT);

                    if (r.userId() != null && r.userId() > 0) h.setLong(3, r.userId());
                    else h.setNull(3, Types.BIGINT);

                    Long channelId = getChannelId(channel);
                    if (channelId != null) h.setLong(4, channelId); else h.setNull(4, Types.BIGINT);

                    h.setString(5, r.value());
                    h.setString(6, subject);
                    h.setString(7, body);
                    h.setString(8, sent ? "SENT" : "FAILED");
                    h.setString(9, sent ? null : (lastEx != null ? lastEx.getMessage() : "Retries exhausted"));

                    h.executeUpdate();
                }
            }
        }

        // Mark event processed
        try (Connection conn = JdbcUtils.getConnection()) {
            markProcessed(conn, eventId);
        } catch (Exception e) {
            logger.error("Failed marking event {} as PROCESSED: {}", eventId, e.getMessage(), e);
        }
    }

    private Long getChannelId(String code) {
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT notification_channel_id
                 FROM notification_channels
                 WHERE notification_channel_code = ? AND is_enabled = TRUE
                 LIMIT 1
             """)) {
            ps.setString(1, code.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("notification_channel_id");
            }
        } catch (SQLException e) {
            logger.error("Failed fetching channel_id for {}: {}", code, e.getMessage(), e);
        }
        return null;
    }

    private String renderTemplate(String tpl, String templateKey, Map<String, Object> payload) throws Exception {
        if (tpl == null || tpl.isBlank()) throw new Exception("No template found for key=" + templateKey);
        Mustache m = new DefaultMustacheFactory().compile(new StringReader(tpl), "tpl");
        StringWriter w = new StringWriter();
        m.execute(w, payload).flush();
        return w.toString();
    }

    private void markProcessed(Connection conn, long eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='PROCESSED', updated_at=NOW() WHERE event_outbox_id=?"
        )) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    private void markFailed(Connection conn, long eventId, String reason) throws SQLException, JsonProcessingException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='FAILED', updated_at=NOW(), payload = payload || ?::jsonb WHERE event_outbox_id=?"
        )) {
            ps.setString(1, mapper.writeValueAsString(Map.of("error", reason)));
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }
}
