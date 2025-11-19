package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Picks queued email_log items and simulates dispatch (or hooks an SMTP provider).
 * This demo marks QUEUED -> SENT and records a sent_at timestamp.
 */
public class NotificationDispatchTask implements ScheduledTask {
    @Override public String name() { return "NotificationDispatchTask"; }
    @Override public long intervalSeconds() { return 20; } // every 20s

    @Override
    public void execute() {
        System.out.println("[Notifier] started");
        String select = "SELECT email_log_id, email, subject, error_message FROM email_log WHERE status = 'QUEUED' FOR UPDATE SKIP LOCKED LIMIT 20";
        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(select)) {
            c.setAutoCommit(false);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("email_log_id");
                    String email = rs.getString("email");
                    String subject = rs.getString("subject");
                    String body = rs.getString("error_message");
                    // In production, call an SMTP or external provider. Here we simulate success.
                    boolean sentOk = simulateSend(email, subject, body);
                    if (sentOk) {
                        try (PreparedStatement up = c.prepareStatement("UPDATE email_log SET status='SENT', created_at = created_at, error_message = NULL WHERE email_log_id = ?")) {
                            up.setLong(1, id);
                            up.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement up = c.prepareStatement("UPDATE email_log SET status='FAILED' WHERE email_log_id = ?")) {
                            up.setLong(1, id);
                            up.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception ex) {
            System.err.println("[Notifier] error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private boolean simulateSend(String to, String subject, String body) {
        // replace this with real SMTP / provider call
        System.out.println("[Notifier] sending to " + to + " subject=" + subject);
        return true;
    }
}
