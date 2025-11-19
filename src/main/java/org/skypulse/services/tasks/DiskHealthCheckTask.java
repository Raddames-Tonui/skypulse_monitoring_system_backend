package org.skypulse.services.tasks;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;
import org.skypulse.utils.SystemInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Checks disk usage on a given path and writes status to system_health.
 */
public class DiskHealthCheckTask implements ScheduledTask {

    private final String path;

    public DiskHealthCheckTask(String path) {
        this.path = path;
    }

    @Override public String name() { return "DiskHealthCheckTask"; }
    @Override public long intervalSeconds() { return 60 * 10; } // every 10 minutes

    @Override
    public void execute() {
        System.out.println("[DiskCheck] started for path: " + path);
        try (Connection c = JdbcUtils.getConnection()) {
            double freePercent = SystemInfo.freeDiskPercent(path);
            long freeBytes = SystemInfo.freeDiskBytes(path);
            String json = String.format("{\"path\":\"%s\",\"free_percent\":%.2f,\"free_bytes\":%d,\"checked_at\":\"%s\"}",
                    path, freePercent, freeBytes, java.time.OffsetDateTime.now().toString());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO system_health(key, value, updated_at) VALUES (?, to_jsonb(?::text)::jsonb, now()) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()")) {
                ps.setString(1, "disk::" + path);
                ps.setString(2, json);
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            System.err.println("[DiskCheck] error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
