package org.skypulse.services.tasks;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.skypulse.services.ScheduledTask;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.Date;

public class NotificationDispatchTask implements ScheduledTask  {

    private static final int COOLDOWN_MINUTES = 10;
//    private final NotificationSender notificationSender;
//
//    public NotificationProcessor(NotificationSender sender) {
//        this.notificationSender = sender;
//    }

    @Override
    public String name() {
        return "[ NotificationDispatchTask ]";
    }

    @Override
    public  long intervalSeconds(){
        return 5;
    }

    @Override
    public void execute() {
        try (Connection conn = JdbcUtils.getConnection()) {
            List<Map<String, Object>> events = fetchPendingEvents(conn);

            for (Map<String, Object> event : events) {
                try {
                    Map<String, Object> payload = JsonUtil.mapper().convertValue(event.get("payload"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                    Long eventId = ((Number) event.get("event_outbox_id")).longValue();
                    String eventType = (String) event.get("event_type");
                    Long serviceId = ((Number) payload.get("service_id")).longValue();

                    if (hasRecentAlert(conn, serviceId, eventType)) {
                        markEventSent(conn, eventId);
                        continue;
                    }

                    Map<String, Object> template = fetchTemplate(conn, eventType);
                    Map<String, Object> templateData = new HashMap<>(payload);

                    if ("SERVICE_RECOVERED".equals(eventType)) {
                        Timestamp lastDown = fetchLastDownTimestamp(conn, serviceId);
                        if (lastDown != null) {
                            Duration downtime = Duration.between(lastDown.toInstant(), new Date().toInstant());
                            templateData.put("downtime", downtime.toMinutes() + " minutes");
                        }
                    }

                    String message = renderTemplate((String) template.get("body_template"), templateData);
                    String subject = renderTemplate((String) template.get("subject_template"), templateData);

                    List<Long> groupIds = fetchGroupIdsByService(conn, serviceId);
                    for (Long groupId : groupIds) {
                        List<Long> memberIds = fetchMemberIdsByGroup(conn, groupId);
                        for (Long memberId : memberIds) {
                            List<Map<String, Object>> channels = fetchEnabledChannels(conn, memberId);
                            for (Map<String, Object> channel : channels) {
                                String channelCode = (String) channel.get("channel_code");
                                Long channelId = ((Number) channel.get("notification_channel_id")).longValue();
                                String destination = channel.get("destination_override") != null ?
                                        (String) channel.get("destination_override") :
                                        fetchDefaultDestination(conn, memberId, channelCode);

//                                boolean sent = notificationSender.send(channelCode, destination, subject, message);
                                logHistory(conn, serviceId, groupId, memberId, channelId, destination, subject, message, "sent");
                            }
                        }
                    }

                    markEventSent(conn, eventId);

                } catch (Exception e) {
                    e.printStackTrace();
                    incrementRetries(conn, ((Number) event.get("event_outbox_id")).longValue());
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private List<Map<String, Object>> fetchPendingEvents(Connection conn) throws SQLException {
        String sql = "SELECT * FROM event_outbox WHERE status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> results = new ArrayList<>();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
            return results;
        }
    }

    private boolean hasRecentAlert(Connection conn, Long serviceId, String eventType) throws SQLException {
        String sql = "SELECT 1 FROM notification_history WHERE service_id = ? AND event_type = ? AND sent_at > NOW() - INTERVAL '? MINUTE' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setString(2, eventType);
            ps.setInt(3, COOLDOWN_MINUTES);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Map<String, Object> fetchTemplate(Connection conn, String eventType) throws SQLException {
        String sql = "SELECT subject_template, body_template FROM notification_templates WHERE event_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> template = new HashMap<>();
                    template.put("subject_template", rs.getString("subject_template"));
                    template.put("body_template", rs.getString("body_template"));
                    return template;
                } else {
                    throw new RuntimeException("Template not found for event type: " + eventType);
                }
            }
        }
    }

    private Timestamp fetchLastDownTimestamp(Connection conn, Long serviceId) throws SQLException {
        String sql = "SELECT sent_at FROM notification_history WHERE service_id = ? AND event_type = 'SERVICE_DOWN' ORDER BY sent_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp("sent_at") : null;
            }
        }
    }

    private List<Long> fetchGroupIdsByService(Connection conn, Long serviceId) throws SQLException {
        String sql = "SELECT contact_group_id FROM service_contact_groups WHERE service_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) ids.add(rs.getLong("contact_group_id"));
                return ids;
            }
        }
    }

    private List<Long> fetchMemberIdsByGroup(Connection conn, Long groupId) throws SQLException {
        String sql = "SELECT user_id FROM contact_group_members WHERE contact_group_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) ids.add(rs.getLong("user_id"));
                return ids;
            }
        }
    }

    private List<Map<String, Object>> fetchEnabledChannels(Connection conn, Long memberId) throws SQLException {
        String sql = "SELECT c.notification_channel_id, nc.notification_channel_code AS channel_code, c.destination_override " +
                "FROM contact_group_member_channels c " +
                "JOIN notification_channels nc ON c.notification_channel_id = nc.notification_channel_id " +
                "WHERE c.contact_group_member_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> channels = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("notification_channel_id", rs.getLong("notification_channel_id"));
                    map.put("channel_code", rs.getString("channel_code"));
                    map.put("destination_override", rs.getString("destination_override"));
                    channels.add(map);
                }
                return channels;
            }
        }
    }

    private String fetchDefaultDestination(Connection conn, Long memberId, String channelCode) {
        // Implement your logic to get default email/phone/telegram handle
        return "default@example.com";
    }

    private void logHistory(Connection conn, Long serviceId, Long groupId, Long memberId,
                            Long channelId, String recipient, String subject, String message, String status) throws SQLException {
        String sql = "INSERT INTO notification_history(service_id, contact_group_id, contact_group_member_id, notification_channel_id, recipient, subject, message, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            ps.setLong(2, groupId);
            ps.setLong(3, memberId);
            ps.setLong(4, channelId);
            ps.setString(5, recipient);
            ps.setString(6, subject);
            ps.setString(7, message);
            ps.setString(8, status);
            ps.executeUpdate();
        }
    }

    private void markEventSent(Connection conn, Long eventId) throws SQLException {
        String sql = "UPDATE event_outbox SET status = 'SENT', last_attempt_at = NOW() WHERE event_outbox_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    private void incrementRetries(Connection conn, Long eventId) throws SQLException {
        String sql = "UPDATE event_outbox SET retries = retries + 1, last_attempt_at = NOW() WHERE event_outbox_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    private String renderTemplate(String template, Map<String, Object> data) {
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "template");
        StringWriter writer = new StringWriter();
        try {
            mustache.execute(writer, data).flush();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render template", e);
        }
        return writer.toString();
    }
}
