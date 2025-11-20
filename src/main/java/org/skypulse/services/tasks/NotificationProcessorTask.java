package org.skypulse.services.tasks;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.notifications.NotificationSender;
import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JsonUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Map;

public class NotificationProcessorTask implements ScheduledTask {

    private final NotificationSender sender;
    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();

    public NotificationProcessorTask(NotificationSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return "[ NotificationProcessorTask ]";
    }

    @Override
    public long intervalSeconds() {
        return 3;
    }

    public void execute() {
        String select = "SELECT event_outbox_id, event_type, payload FROM event_outbox " +
                "WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED LIMIT 50";

        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(select)) {

            c.setAutoCommit(false);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long eventId = rs.getLong("event_outbox_id");
                    String eventType = rs.getString("event_type");
                    String payloadJson = rs.getString("payload");

                    try {
                        // Handle JSON array or object payloads
                        com.fasterxml.jackson.databind.JsonNode node = JsonUtil.mapper().readTree(payloadJson);

                        if (node.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                                if (item.isObject()) {
                                    Map<String, Object> payload = JsonUtil.mapper().convertValue(
                                            item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                    processEvent(c, eventId, eventType, payload);
                                } else {
                                    System.err.println("[NotificationProcessorTask] Skipping non-object payload: " + item);
                                }
                            }
                        } else if (node.isObject()) {
                            Map<String, Object> payload = JsonUtil.mapper().convertValue(
                                    node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            processEvent(c, eventId, eventType, payload);
                        } else {
                            System.err.println("[NotificationProcessorTask] Invalid payload format: " + payloadJson);
                        }

                        markProcessed(c, eventId);

                    } catch (Exception e) {
                        markFailed(c, eventId, e.getMessage());
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processEvent(Connection c, long eventId, String eventType, Map<String, Object> payload) throws Exception {

        // 1. Load template info
        PreparedStatement ps = c.prepareStatement(
                "SELECT subject_template, body_template, body_template_key, storage_mode FROM notification_templates WHERE event_type = ?"
        );
        ps.setString(1, eventType);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) throw new Exception("Template not found for event_type=" + eventType);

        String subjectTpl = rs.getString("subject_template");
        String bodyTpl = rs.getString("body_template");
        String bodyKey = rs.getString("body_template_key");
        String storageMode = rs.getString("storage_mode");

        // 2. Load from filesystem if needed
        if (("filesystem".equalsIgnoreCase(storageMode) || "hybrid".equalsIgnoreCase(storageMode))
                && bodyKey != null && !bodyKey.isBlank()) {
            try {
                bodyTpl = Files.readString(Paths.get("src/main/resources/templates/" + bodyKey));
            } catch (IOException ex) {
                System.err.println("[NotificationProcessorTask] Failed to read template file, using DB template. " + ex.getMessage());
            }
        }

        // 3. Render template using Mustache
        String subject = renderTemplate(subjectTpl, payload);
        String body = renderTemplate(bodyTpl, payload);

        // 4. Fetch contact groups and members
        PreparedStatement groupPs = c.prepareStatement(
                "SELECT cgm.contact_group_member_id, u.email " +
                        "FROM contact_group_members cgm " +
                        "JOIN users u ON cgm.user_id = u.user_id " +
                        "JOIN contact_groups cg ON cgm.contact_group_id = cg.contact_group_id " +
                        "WHERE cg.is_deleted = false"
        );

        ResultSet groupRs = groupPs.executeQuery();
        while (groupRs.next()) {
            String email = groupRs.getString("email");

            // 5. Send via Email (inline image example)
            Map<String, String> inlineImages = Map.of(
                    "tatuaLogo", "src/main/resources/images/tatua-logo.png"
            );

            boolean ok = sender.send("EMAIL", email, subject, body, inlineImages);

            // 6. Log to notification_history
            try (PreparedStatement logPs = c.prepareStatement(
                    "INSERT INTO notification_history(service_id, contact_group_id, contact_group_member_id, notification_channel_id, recipient, subject, message, status, sent_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())"
            )) {
                logPs.setObject(1, payload.get("service_id"));
                logPs.setObject(2, null);
                logPs.setObject(3, groupRs.getLong("contact_group_member_id"));
                logPs.setObject(4, 1); // email channel
                logPs.setString(5, email);
                logPs.setString(6, subject);
                logPs.setString(7, body);
                logPs.setString(8, ok ? "sent" : "failed");
                logPs.executeUpdate();
            }
        }
    }

    private String renderTemplate(String template, Map<String, Object> data) throws Exception {
        Mustache mustache = mustacheFactory.compile(new StringReader(template), "template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, data).flush();
        return writer.toString();
    }

    private void markProcessed(Connection c, long eventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_outbox SET status='PROCESSED', updated_at=now() WHERE event_outbox_id = ?"
        )) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    private void markFailed(Connection c, long eventId, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_outbox SET status='FAILED', updated_at=now(), payload = payload || to_jsonb(?::text) WHERE event_outbox_id = ?"
        )) {
            ps.setString(1, "{\"error\":\"" + reason + "\"}");
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }
}
