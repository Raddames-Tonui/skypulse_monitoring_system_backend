package org.skypulse;


import org.skypulse.config.ConfigLoader;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.utils.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {

        LogContext.start("Main");
        try {
            logger.info("Starting SkyPulse System...");

            // 1️ Load encrypted configuration (decrypted in memory)
            String configPath = (args.length > 0) ? args[0] : "config.xml";
            XmlConfiguration cfg = ConfigLoader.loadConfig(configPath);

            // 2️ Initialize database first
            logger.info("Initializing database connection...");
            DatabaseManager.initialize(cfg);

            // 3️ Start Undertow server (if DB connected successfully)
            logger.info("Starting Undertow server...");
//            RestAPIServer.startUndertow(cfg);  // Quick start test for server and Db
//            RestAPIServer.start(cfg);

        } catch (Exception e) {
            logger.error("System startup failed: {} ", e.getMessage(), e);
            System.exit(1);
        } finally {
            LogContext.clear();
        }

        // Shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(DatabaseManager::shutdown));
    }

}
