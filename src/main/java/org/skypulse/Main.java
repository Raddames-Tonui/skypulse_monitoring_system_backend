package org.skypulse;

import org.skypulse.config.ConfigLoader;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.DBTaskScheduler;
import org.skypulse.tasks.TaskScheduler;
import org.skypulse.tasks.tasks.*;
import org.skypulse.config.utils.LogContext;
import org.skypulse.rest.RestApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyPulse Main Entry Point (Degraded Startup Mode)
 * - Loads configuration
 * - Initializes DB if available
 * - Starts Undertow REST API
 * - Schedules DB reconnect in background if needed
 * - Runs all application tasks via TaskScheduler
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // Unified application task scheduler
    private static final TaskScheduler appScheduler = new TaskScheduler(5); // 5 threads

    public static void main(String[] args) {
        LogContext.start("Main");
        try {
            logger.info("Starting SkyPulse System...");

            String configPath = (args.length > 0) ? args[0] : "config.xml";
            XmlConfiguration cfg = ConfigLoader.loadConfig(configPath);
            logger.debug("Configuration loaded from {}", configPath);

            boolean dbAvailable = false;
            try {
                DatabaseManager.initialize(cfg);
                dbAvailable = true;
            } catch (Exception e) {
                logger.error("Database initialization failed: {}", e.getMessage());
                logger.warn("Continuing in DEGRADED MODE — database unavailable.");
            }

            // Start REST API (always)
            logger.info("Starting Undertow server...");
            RestApiServer.startUndertow(cfg);

            // --- Register application tasks ---
            registerApplicationTasks(dbAvailable);

            // Start the task scheduler
            appScheduler.start();

            // --- Schedule background DB reconnection if DB is offline ---
            if (!dbAvailable) {
                logger.info("Starting background DB reconnection monitor...");
                DBTaskScheduler.scheduleReconnect(() -> {
                    try {
                        DatabaseManager.initialize(cfg);
                        logger.info("Database reconnected successfully.");
                        // Once DB is back, activate DB-backed tasks
                        activateDbBackedTasks();
                    } catch (Exception ignored) {}
                });
            }

            // --- Shutdown hook ---
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown initiated...");
                appScheduler.stop();
                DBTaskScheduler.shutdown();
                DatabaseManager.shutdown();
                logger.info("SkyPulse System shutdown complete.");
            }));

        } catch (Exception e) {
            logger.error("System startup failed: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            LogContext.clear();
        }
    }

    /**
     * Register all application tasks (system + monitoring)
     */
    private static void registerApplicationTasks(boolean dbAvailable) {
        logger.info("Registering application tasks...");

        // Monitoring tasks
        appScheduler.register(new UptimeCheckTask());
        appScheduler.register(new SslExpiryMonitorTask());

        // System / maintenance tasks
        appScheduler.register(new DiskHealthCheckTask("/"));
        appScheduler.register(new LogRetentionCleanupTask());

        if (dbAvailable) {
            activateDbBackedTasks();
        }

        logger.info("Application tasks registered.");
    }

    /**
     * DB-backed tasks activated only when DB is online.
     */
    private static void activateDbBackedTasks() {
        logger.info("Activating DB-backed tasks...");
        appScheduler.register(new EventQueueProcessorTask());
        appScheduler.register(new NotificationDispatchTask());
        logger.info("DB-backed tasks activated.");
    }
}
