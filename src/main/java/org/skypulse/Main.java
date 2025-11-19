package org.skypulse;

import org.skypulse.config.ConfigLoader;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.DBTaskScheduler;
import org.skypulse.services.TaskScheduler;
import org.skypulse.services.tasks.*;
import org.skypulse.config.utils.LogContext;
import org.skypulse.rest.RestApiServer;
import org.skypulse.utils.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.skypulse.services.ApplicationTasks.activateDbBackedTasks;
import static org.skypulse.services.ApplicationTasks.registerApplicationTasks;

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

    public static final TaskScheduler appScheduler = new TaskScheduler(5); // 5 threads

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


            logger.info("Starting Undertow server...");
            RestApiServer.startUndertow(cfg);

            // --- Register application tasks and start ---
            registerApplicationTasks(dbAvailable);
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


}
