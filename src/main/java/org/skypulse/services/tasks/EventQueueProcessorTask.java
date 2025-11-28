package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.config.database.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Event processor that reads from event_outbox (instead of event_queue)
 * and updates status to PROCESSED or FAILED.
 */
public class EventQueueProcessorTask implements ScheduledTask {


    @Override public String name() { return "EventQueueProcessorTask"; }
    @Override public long intervalSeconds() { return 30; }

    @Override
    public void execute() {
        System.out.println("[EventOutbox] started");
        String select = "SELECT event_outbox_id, event_type, payload FROM event_outbox WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED LIMIT 50";
        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(select)) {
            c.setAutoCommit(false);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("event_outbox_id");
                    String type = rs.getString("event_type");
                    String payload = rs.getString("payload");
                    try {
                        switch (type) {
                            case "EMAIL" -> processEmail(c, id, payload);
                            case "WEBHOOK" -> processWebhook(c, id, payload);
                            case "UPTIME_CHECK", "SSL_CHECK" -> markProcessed(c, id);
                            default -> markFailed(c, id, "Unknown event type");
                        }
                    } catch (Exception e) {
                        markFailed(c, id, e.getMessage());
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
            System.err.println("[EventOutbox] error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processEmail(Connection c, long id, String payload) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO email_log(user_id, email, subject, status, error_message, created_at) VALUES (?, ?, ?, ?, ?, now())")) {
            ps.setNull(1, java.sql.Types.NULL);
            ps.setString(2, extract(payload, "to"));
            ps.setString(3, extract(payload, "subject"));
            ps.setString(4, "QUEUED");
            ps.setString(5, extract(payload, "body"));
            ps.executeUpdate();
        }
        markProcessed(c, id);
    }

    private void processWebhook(Connection c, long id, String payload) throws Exception {
        markProcessed(c, id);
    }

    private static String extract(String payload, String key) {
        if (payload == null) return null;
        int pos = payload.indexOf("\"" + key + "\"");
        if (pos < 0) return null;
        int colon = payload.indexOf(":", pos);
        int q1 = payload.indexOf("\"", colon);
        int q2 = payload.indexOf("\"", q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        return payload.substring(q1 + 1, q2);
    }

    private void markProcessed(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_outbox SET status='PROCESSED', updated_at=now() WHERE event_outbox_id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void markFailed(Connection c, long id, String reason) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE event_outbox SET status='FAILED', updated_at=now(), payload = payload || to_jsonb(?::text) WHERE event_outbox_id = ?")) {
            ps.setString(1, "{\"error\":\"" + reason + "\"}");
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
