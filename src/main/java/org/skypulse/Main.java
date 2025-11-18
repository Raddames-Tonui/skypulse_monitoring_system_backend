package org.skypulse;

import org.skypulse.config.ConfigLoader;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.TaskScheduler;
import org.skypulse.rest.RestApiServer;
import org.skypulse.utils.security.CryptoInit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkyPulse Main Entry Point (Degraded Startup Mode)
 * - Loads configuration and environment
 * - Initializes DB if available, otherwise runs degraded
 * - Starts Undertow REST API regardless of DB state
 * - Periodically retries DB connection in the background
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {

        try {
            logger.info("Starting SkyPulse System...");

            CryptoInit.init();

            String configPath = (args.length > 0) ? args[0] : "config.xml";
            XmlConfiguration cfg = ConfigLoader.loadConfig(configPath);
            logger.debug("Configuration loaded successfully from {}", configPath);

            boolean dbAvailable = false;
            try {
                DatabaseManager.initialize(cfg);
                dbAvailable = true;
            } catch (Exception e) {
                logger.error("Database initialization failed: {}", e.getMessage());
                logger.warn("Continuing startup in DEGRADE MODE — database unavailable.");
            }

            logger.info("Starting Undertow server...");
            RestApiServer.startUndertow(cfg);

            if (!dbAvailable) {
                logger.info("Starting background reconnection monitor...");
                TaskScheduler.scheduleReconnect(() -> {
                    try {
                        DatabaseManager.initialize(cfg);
                        logger.info("Database reconnected successfully.");
                    } catch (Exception ignored) {
                        // silent retry
                    }
                });
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown initiated...");
                TaskScheduler.shutdown();
                DatabaseManager.shutdown();
                logger.info("SkyPulse System shutdown gracefully.");
            }));

        } catch (Exception e) {
            logger.error("System startup failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}