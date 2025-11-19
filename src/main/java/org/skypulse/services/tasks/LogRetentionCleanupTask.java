package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Removes or archives audit_log and email_log entries older than retentionDays.
 * For safety we only remove older than a configured retention.
 */
public class LogRetentionCleanupTask implements ScheduledTask {

    private final int retentionDays;

    public LogRetentionCleanupTask() {
        this.retentionDays = 90; // default 90 days — make configurable
    }

    @Override public String name() { return "LogRetentionCleanupTask"; }
    @Override public long intervalSeconds() { return 60 * 60 * 24; } // daily

    @Override
    public void execute() {
        System.out.println("[LogCleanup] started");
        String auditDel = "DELETE FROM audit_log WHERE created_at < (CURRENT_TIMESTAMP - INTERVAL '" + retentionDays + " days')";
        String emailDel = "DELETE FROM email_log WHERE created_at < (CURRENT_TIMESTAMP - INTERVAL '" + retentionDays + " days') AND status <> 'SENT'";
        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement pa = c.prepareStatement(auditDel);
             PreparedStatement pe = c.prepareStatement(emailDel)) {
            c.setAutoCommit(false);
            int a = pa.executeUpdate();
            int e = pe.executeUpdate();
            c.commit();
            System.out.println("[LogCleanup] removed audit:" + a + " emailPending:" + e);
        } catch (Exception ex) {
            System.err.println("[LogCleanup] error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
