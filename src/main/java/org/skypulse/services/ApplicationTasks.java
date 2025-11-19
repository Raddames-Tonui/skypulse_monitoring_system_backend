package org.skypulse.services;

import org.skypulse.Main;
import org.skypulse.services.tasks.EventQueueProcessorTask;
import org.skypulse.services.tasks.UptimeCheckTask;
import org.skypulse.utils.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.skypulse.Main.appScheduler;

public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);


    /**
     * Register all application tasks (system + monitoring)
     */
    public static void registerApplicationTasks(boolean dbAvailable) {
        logger.info("Registering application tasks...");


        // dynamic registration from DB:
        if (dbAvailable) {
            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT monitored_service_id, monitored_service_name, monitored_service_url, check_interval, retry_count, retry_delay, expected_status_code " +
                                 "FROM monitored_services WHERE is_active = TRUE")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    appScheduler.register(new UptimeCheckTask(
                            rs.getLong("monitored_service_id"),
                            rs.getString("monitored_service_name"),
                            rs.getString("monitored_service_url"),
                            rs.getInt("check_interval"),
                            rs.getInt("retry_count"),
                            rs.getInt("retry_delay"),
                            rs.getInt("expected_status_code")
                    ));
                }
            } catch (Exception e) {
                logger.error("Failed to register UptimeCheckTasks", e);
            }
        }

        // System / maintenance tasks
        // appScheduler.register(new DiskHealthCheckTask("/"));
        // appScheduler.register(new LogRetentionCleanupTask());

        if (dbAvailable) {
            activateDbBackedTasks();
        }

        logger.info("Application tasks registered.");
    }


    /**
     * DB-backed tasks activated only when DB is online.
     */
    public static void activateDbBackedTasks() {
        logger.info("Activating DB-backed tasks...");
        appScheduler.register(new EventQueueProcessorTask());
//        appScheduler.register(new NotificationDispatchTask());
        logger.info("DB-backed tasks activated.");
    }
}
