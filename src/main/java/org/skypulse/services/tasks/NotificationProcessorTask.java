package org.skypulse.services.tasks;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.dtos.SystemSettings.SystemDefaults;
import org.skypulse.notifications.NotificationSender;
import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class NotificationProcessorTask implements ScheduledTask {

    private static final Logger logger = LoggerFactory.getLogger(NotificationProcessorTask.class);
    private final NotificationSender sender;
    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();
    private static final ObjectMapper mapper = JsonUtil.mapper();
    private final SystemDefaults systemDefaults;
    private final int intervalSeconds;

    public NotificationProcessorTask(NotificationSender sender, SystemDefaults systemDefaults) {
        this.sender = sender;
        this.systemDefaults = systemDefaults;
        this.intervalSeconds = Math.max(systemDefaults.notificationCheckInterval(), 3);
    }

    @Override
    public String name() {
        return "[ NotificationProcessorTask ]";
    }

    @Override
    public long intervalSeconds() {
        return intervalSeconds;
    }

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
                        if (node.isArray()) {
                            for (JsonNode item : node) {
                                if (item.isObject()) {
                                    Map<String, Object> payload = mapper.convertValue(item,
                                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                                    processEvent(conn, eventId, serviceId, eventType, payload, firstFailureAt, cooldownMinutes, retryCount, retryDelaySeconds);
                                }
                            }
                        } else if (node.isObject()) {
                            Map<String, Object> payload = mapper.convertValue(node,
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                            processEvent(conn, eventId, serviceId, eventType, payload, firstFailureAt, cooldownMinutes, retryCount, retryDelaySeconds);
                        }
                        markProcessed(conn, eventId);
                    } catch (Exception e) {
                        logger.error("[NotificationProcessorTask] Failed processing event {}: {}", eventId, e.getMessage(), e);
                        try { markFailed(conn, eventId, e.getMessage()); } catch (SQLException ex) {
                            logger.error("[NotificationProcessorTask] Failed to mark event {} failed: {}", eventId, ex.getMessage(), ex);
                        }
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logger.error("[NotificationProcessorTask] Failed batch processing: {}", e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (Exception e) {
            logger.error("[NotificationProcessorTask] Database connection error: {}", e.getMessage(), e);
        }
    }

    private void processEvent(Connection conn, long eventId, long serviceId, String eventType,
                              Map<String, Object> payload, Timestamp firstFailureAt,
                              int cooldownMinutes, int retryCount, int retryDelaySeconds) throws Exception {

        logger.debug("[NotificationProcessorTask] processEvent start: eventId={}, serviceId={}, eventType={}", eventId, serviceId, eventType);

        boolean sendEvent = true;
        Duration downtime = Duration.ZERO;

        // Cooldown handling for repeated DOWN alerts
        if ("SERVICE_DOWN".equals(eventType)) {
            if (firstFailureAt != null &&
                    firstFailureAt.toInstant().plus(Duration.ofMinutes(cooldownMinutes)).isAfter(Instant.now())) {
                logger.info("[NotificationProcessorTask] Skipping SERVICE_DOWN for service {} within cooldown (first_failure_at={})", serviceId, firstFailureAt);
                sendEvent = false;
            } else if (firstFailureAt == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE event_outbox SET first_failure_at = NOW() WHERE event_outbox_id = ?")) {
                    ps.setLong(1, eventId);
                    ps.executeUpdate();
                    logger.debug("[NotificationProcessorTask] Set first_failure_at for event {}", eventId);
                }
            }
        }

        // Downtime calculation for recovery
        if ("SERVICE_RECOVERED".equals(eventType) && firstFailureAt != null) {
            downtime = Duration.between(firstFailureAt.toInstant(), Instant.now());
            payload.put("downtime_seconds", downtime.getSeconds());
            logger.debug("[NotificationProcessorTask] Calculated downtime_seconds={} for event {}", downtime.getSeconds(), eventId);
        }

        if (!sendEvent) return;

        // Load templates (external -> classpath -> db)
        String subjectTpl;
        String bodyTpl;
        try (PreparedStatement psTpl = conn.prepareStatement(
                "SELECT subject_template, body_template, body_template_key, storage_mode " +
                        "FROM notification_templates WHERE event_type = ?")) {
            psTpl.setString(1, eventType);
            try (ResultSet rsTpl = psTpl.executeQuery()) {
                if (rsTpl.next()) {
                    subjectTpl = rsTpl.getString("subject_template");
                    String bodyDb = rsTpl.getString("body_template");
                    String bodyKey = rsTpl.getString("body_template_key");
                    String storageMode = rsTpl.getString("storage_mode");
                    bodyTpl = loadTemplate(bodyDb, bodyKey, storageMode);
                    if (bodyTpl == null || bodyTpl.isBlank()) {
                        throw new Exception("Resolved template is empty for event_type=" + eventType);
                    }
                } else {
                    throw new Exception("Template not found for event_type=" + eventType);
                }
            }
        }

        String subject = renderTemplate(subjectTpl, payload);
        String body = renderTemplate(bodyTpl, payload);

        // Fetch contact_group_id and user contacts for the service
        String contactsSql = """
                SELECT cgm.contact_group_id, uc.user_contacts_id, uc.user_id, uc.type, uc.value, uc.is_primary
                FROM monitored_services_contact_groups mscg
                JOIN contact_group_members cgm ON mscg.contact_group_id = cgm.contact_group_id
                JOIN user_contacts uc ON cgm.user_id = uc.user_id
                WHERE mscg.monitored_service_id = ?
                """;

        try (PreparedStatement cgPs = conn.prepareStatement(contactsSql)) {
            cgPs.setLong(1, serviceId);
            try (ResultSet cgRs = cgPs.executeQuery()) {
                while (cgRs.next()) {
                    long contactGroupId = cgRs.getLong("contact_group_id");
                    long contactId = cgRs.getLong("user_contacts_id");
                    long userId = cgRs.getLong("user_id");
                    String type = cgRs.getString("type");
                    String destination = cgRs.getString("value");

                    // Inline images map — you can make this dynamic per template if you want
                    Map<String, String> inlineImages = Map.of("tatuaLogo", "src/main/resources/images/tatua-logo.png");

                    boolean sent = false;
                    int attempt = 0;
                    Exception lastSendException = null;

                    // Try sending using user contact type (EMAIL, TELEGRAM, SMS, etc.)
                    while (!sent && attempt < retryCount) {
                        try {
                            sent = sender.send(type.toUpperCase(), destination, subject, body, inlineImages);
                        } catch (Exception e) {
                            lastSendException = e;
                            sent = false;
                        }
                        attempt++;
                        if (!sent) {
                            logger.warn("[NotificationProcessorTask] Send attempt {} failed for contact {} (type={}) destination={}", attempt, contactId, type, destination);
                            try { Thread.sleep(retryDelaySeconds * 1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    }

                    // Insert history record (notification_channel_id left NULL; contact_group_id set)
                    try (PreparedStatement logPs = conn.prepareStatement(
                            "INSERT INTO notification_history(service_id, contact_group_id, contact_group_member_id, " +
                                    "notification_channel_id, recipient, subject, message, status, sent_at, error_message) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)"
                    )) {
                        logPs.setLong(1, serviceId);
                        logPs.setObject(2, contactGroupId);
                        logPs.setLong(3, userId);
                        logPs.setObject(4, null); // you can map type->notification_channel_id if you maintain that table
                        logPs.setString(5, destination);
                        logPs.setString(6, subject);
                        logPs.setString(7, body);
                        logPs.setString(8, sent ? "sent" : "failed");
                        String err = sent ? null : (lastSendException != null ? lastSendException.getMessage() : "Retries exhausted");
                        logPs.setString(9, err);
                        logPs.executeUpdate();
                    } catch (SQLException e) {
                        logger.error("[NotificationProcessorTask] Failed to log notification history for contact {}: {}", contactId, e.getMessage(), e);
                    }

                    if (sent) {
                        logger.info("[NotificationProcessorTask] Notification sent: eventId={}, serviceId={}, contactId={}, type={}, dest={}", eventId, serviceId, contactId, type, destination);
                    } else {
                        logger.warn("[NotificationProcessorTask] Notification NOT sent after {} attempts: eventId={}, serviceId={}, contactId={}, type={}, dest={}", attempt, eventId, serviceId, contactId, type, destination);
                    }
                }
            }
        }
    }

    private String renderTemplate(String template, Map<String, Object> data) throws Exception {
        Mustache mustache = mustacheFactory.compile(new StringReader(template), "template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, data).flush();
        return writer.toString();
    }

    private void markProcessed(Connection conn, long eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='PROCESSED', updated_at=NOW() WHERE event_outbox_id = ?"
        )) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    private void markFailed(Connection conn, long eventId, String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE event_outbox SET status='FAILED', updated_at=NOW(), payload = payload || to_jsonb(?::text) WHERE event_outbox_id = ?"
        )) {
            ps.setString(1, "{\"error\":\"" + reason.replace("\"", "'") + "\"}");
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }

    /**
     * Load templates in order:
     * 1) external folder (notification.templates.path system property or ./templates)
     * 2) classpath resource under /templates/
     * 3) DB fallback (bodyTemplateDb)
     */
    private String loadTemplate(String bodyTemplateDb, String bodyTemplateKey, String storageMode) {
        String template = null;

        // 1) External folder
        String externalPath = System.getProperty("notification.templates.path", System.getProperty("user.dir") + "/templates");
        if (bodyTemplateKey != null && !bodyTemplateKey.isBlank()) {
            try {
                template = Files.readString(Paths.get(externalPath, bodyTemplateKey));
                if (template != null && !template.isBlank()) {
                    logger.info("[NotificationProcessorTask] Loaded template from external folder: {}", bodyTemplateKey);
                    return template;
                }
            } catch (Exception e) {
                logger.warn("[NotificationProcessorTask] External template not found or failed to read, will try classpath. Key: {} - {}", bodyTemplateKey, e.getMessage());
            }
        }

        // 2) Classpath
        if (bodyTemplateKey != null && !bodyTemplateKey.isBlank()) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("templates/" + bodyTemplateKey)) {
                if (is != null) {
                    template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    logger.info("[NotificationProcessorTask] Loaded template from classpath: {}", bodyTemplateKey);
                    return template;
                }
            } catch (Exception e) {
                logger.warn("[NotificationProcessorTask] Classpath template failed to load, will try DB. Key: {} - {}", bodyTemplateKey, e.getMessage());
            }
        }

        // 3) DB fallback
        if (bodyTemplateDb != null && !bodyTemplateDb.isBlank()) {
            template = bodyTemplateDb;
            logger.info("[NotificationProcessorTask] Using DB template as fallback.");
        } else {
            logger.error("[NotificationProcessorTask] No template found in external folder, classpath, or DB for key: {}", bodyTemplateKey);
        }

        return template;
    }
}
