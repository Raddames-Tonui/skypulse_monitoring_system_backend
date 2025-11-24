package org.skypulse;

import org.skypulse.config.ConfigLoader;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.config.database.DBTaskScheduler;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.utils.LogContext;
import org.skypulse.rest.RestApiServer;
import org.skypulse.services.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.skypulse.services.ApplicationTasks.registerApplicationTasks;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final TaskScheduler appScheduler = new TaskScheduler(10);

    public static void main(String[] args) {
        LogContext.start("Main");
        try {
            logger.info("[------------Starting SkyPulse System------------]");

            String configPath = (args.length > 0) ? args[0] : "config.xml";
            XmlConfiguration cfg = ConfigLoader.loadConfig(configPath);
            logger.debug("Configuration loaded from {}", configPath);

            if (cfg.notification != null && cfg.notification.email != null) {
                XmlConfiguration.Notification.Email emailCfg = cfg.notification.email;
                logger.info("Email config loaded: host={}, port={}, TLS={}, username={}, from={}",
                        emailCfg.smtpHost,
                        emailCfg.smtpPort,
                        emailCfg.useTLS,
                        emailCfg.username,
                        emailCfg.fromAddress);
            } else {
                logger.warn("Email configuration is missing or incomplete!");
            }

            boolean dbAvailable = false;
            try {
                DatabaseManager.initialize(cfg);
                dbAvailable = true;
            } catch (Exception e) {
                logger.error("Database initialization failed: {}", e.getMessage());
                logger.warn("[------------Continuing in DEGRADED MODE — database unavailable------------]");
            }

            logger.info("[------------Starting Undertow server------------]");
            RestApiServer.startUndertow(cfg);

            // --- Register tasks using ApplicationTasks ---
            registerApplicationTasks(dbAvailable, cfg);
            appScheduler.start();

            if (!dbAvailable) {
                logger.info("[------------Starting background DB reconnection monitor------------]");
                DBTaskScheduler.scheduleReconnect(() -> {
                    try {
                        DatabaseManager.initialize(cfg);
                        logger.info("[------------ Database reconnected successfully ------------]");
                        registerApplicationTasks(true, cfg);
                    } catch (Exception ignored) {}
                });
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("[------------ Shutdown initiated ------------]");
                appScheduler.shutdown();
                DBTaskScheduler.shutdown();
                DatabaseManager.shutdown();
                logger.info("[------------ SkyPulse System shutdown complete ------------]");
            }));

        } catch (Exception e) {
            logger.error("[------------ System startup failed: {} ------------]", e.getMessage(), e);
            System.exit(1);
        } finally {
            LogContext.clear();
        }
    }
}
