package org.skypulse.services;

import org.skypulse.services.tasks.EventQueueProcessorTask;
import org.skypulse.services.tasks.SslExpiryMonitorTask;
import org.skypulse.services.tasks.UptimeCheckTask;
import org.skypulse.utils.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.List;

import static org.skypulse.Main.appScheduler;

/**
 * Register all Services (system + monitoring)
 */
public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);


    public static void registerApplicationTasks(boolean dbAvailable) {
        logger.info("Registering application tasks...");

        if (dbAvailable) {
            try (Connection conn = JdbcUtils.getConnection()) {
                SystemSettings.SystemDefaults defaults = SystemSettings.loadSystemDefaults(conn);
                List<SystemSettings.ServiceConfig> services = SystemSettings.loadActiveServices(conn);

                // Register UptimeCheckTask for each service
                for (SystemSettings.ServiceConfig service : services) {
                    appScheduler.register(new UptimeCheckTask(service, defaults));
                }

                logger.info("Registered {} UptimeCheckTasks with system defaults.", services.size());

            } catch (Exception e) {
                logger.error("Failed to register UptimeCheckTasks", e);
            }
        }

        // System / maintenance tasks (DB-independent)
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
        appScheduler.register(new SslExpiryMonitorTask());
        // appScheduler.register(new NotificationDispatchTask());
        logger.info("DB-backed tasks activated.");
    }
}
