package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Picks events marked FAILED and attempts a limited number of retries.
 * For simplicity, this implementation will attempt a single retry and set them to PENDING again.
 * In production, you'd persist attempt counts and backoff strategy.
 */
public class RetryFailedOperationsTask implements ScheduledTask {

    @Override public String name() { return "RetryFailedOperationsTask"; }
    @Override public long intervalSeconds() { return 60 * 5; } // every 5 minutes

    @Override
    public void execute() {
        System.out.println("[RetryTask] started");
        String select = "SELECT event_id, event_type, payload FROM event_queue WHERE status = 'FAILED' FOR UPDATE SKIP LOCKED LIMIT 20";
        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(select)) {
            c.setAutoCommit(false);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getObject("event_id").toString();
                    String type = rs.getString("event_type");
                    String payload = rs.getString("payload");
                    // Very simple retry: set status back to PENDING for the event processor to pick up
                    try (PreparedStatement up = c.prepareStatement("UPDATE event_queue SET status='PENDING', processed_at = NULL WHERE event_id = ?")) {
                        up.setObject(1, java.util.UUID.fromString(id));
                        up.executeUpdate();
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
            System.err.println("[RetryTask] error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
