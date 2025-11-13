package org.skypulse.config.utils;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * KeyProvider acts as the universal environment bootstrap.
 *
 * Responsibilities:
 *  1️ Loads environment variables (.env or system)
 *  2️ Initializes correct Logback config (dev/prod)
 *  3️ Provides encryption key securely
 */
public class KeyProvider {

    private static final Logger logger = LoggerFactory.getLogger(KeyProvider.class);

    private static final String ENV_KEY_NAME = "CONFIG_MASTER_KEY";
    private static final String ENV_ENVIRONMENT = "APP_ENV";
    private static boolean initialized = false;
    private static String activeEnv = "PRODUCTION";

    /** Initialize environment, logger config, and keys */
    private static synchronized void init() {
        if (initialized) return;

        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

            // --- Determine environment ---
            String env = System.getenv(ENV_ENVIRONMENT);
            if (env == null || env.isBlank()) {
                env = dotenv.get(ENV_ENVIRONMENT, "PRODUCTION");
            }
            activeEnv = env.toUpperCase();
            System.setProperty(ENV_ENVIRONMENT, activeEnv);

            // --- Switch Logback config dynamically ---
            if ("DEVELOPMENT".equalsIgnoreCase(activeEnv)) {
                loadLogbackFromClasspath("logback-dev.xml");
                logger.info("Environment set to DEVELOPMENT — using logback-dev.xml");
            } else {
                loadLogbackFromClasspath("logback.xml");
                logger.info("Environment set to PRODUCTION — using logback.xml");
            }

            logger.info("Environment initialized successfully: {}", activeEnv);
            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize KeyProvider environment: {}", e.getMessage(), e);
            throw new IllegalStateException("Environment initialization failed.", e);
        }
    }

    /** Universal method to resolve encryption key */
    public static String getEncryptionKey() {
        if (!initialized) init();

        String key = System.getenv(ENV_KEY_NAME);

        if (key == null || key.isBlank()) {
            try {
                Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
                key = dotenv.get(ENV_KEY_NAME);
            } catch (Exception e) {
                logger.warn("Unable to read encryption key from .env file: {}", e.getMessage(), e);
            }
        }

        if (key == null || key.isBlank()) {
            logger.error("Encryption key not found in environment or .env");
            throw new IllegalStateException(
                    """
                    Encryption key not found.
                    Set it using one of these methods:
                      • Environment variable: CONFIG_MASTER_KEY
                      • .env file: CONFIG_MASTER_KEY=YourStrongKey
                      • Secure file: use getKeyFromFile(path)
                    """
            );
        }

        logger.debug("Encryption key successfully resolved ({} chars).", key.length());
        return key.trim();
    }

    /** Reads key from secure file (for containers or secrets mounts) */
    public static String getKeyFromFile(String filePath) throws IOException {
        if (!initialized) init();

        Path path = Paths.get(filePath);
        String key = Files.readString(path).trim();

        if (key.isEmpty()) {
            logger.error("Key file '{}' is empty!", path);
            throw new IOException("Key file is empty: " + path);
        }

        logger.info("Loaded encryption key from secure file: {}", path);
        return key;
    }

    /** Convenience helpers */
    public static boolean isDev() {
        if (!initialized) init();
        return "DEVELOPMENT".equalsIgnoreCase(activeEnv);
    }

    public static boolean isProd() {
        return !isDev();
    }

    public static String getEnvironment() {
        if (!initialized) init();
        return activeEnv;
    }

    private static void loadLogbackFromClasspath(String resourceName) {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(KeyProvider.class.getClassLoader().getResourceAsStream(resourceName));
            StatusPrinter.printInCaseOfErrorsOrWarnings(context);
        } catch (Exception e) {
            System.err.println("Failed to load logback config: " + resourceName + " (" + e.getMessage() + ")");
        }
    }

}
