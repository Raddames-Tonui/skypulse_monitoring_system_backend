package org.skypulse.config;

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
 *  1 Loads environment variables (.env or system)
 *  2 Initializes correct Logback config (dev/prod)
 *  3 Provides encryption key securely
 */
public class KeyProvider {

    private static final String ENV_KEY_NAME = "CONFIG_MASTER_KEY";
    private static final String ENV_ENVIRONMENT = "APP_ENV";
    private static boolean initialized = false;

    // Logger (may initialize after logback switch)
    private static Logger logger = LoggerFactory.getLogger(KeyProvider.class);

    // Cached environment state
    private static String activeEnv = "PROD";

    /** Initialize environment, logger config, and keys */
    private static synchronized void init() {
        if (initialized) return;

        // Load .env file first (safe in dev)
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        // Determine environment
        String env = System.getenv(ENV_ENVIRONMENT);
        if (env == null || env.isBlank()) {
            env = dotenv.get(ENV_ENVIRONMENT, "PROD");
        }
        activeEnv = env.toUpperCase();

        // Set global system properties for consistency
        System.setProperty(ENV_ENVIRONMENT, activeEnv);

        // Switch logback config before other loggers initialize
        if ("DEV".equalsIgnoreCase(activeEnv)) {
            System.setProperty("logback.configurationFile", "src/main/resources/logback-dev.xml");
            System.out.println("[KeyProvider] Environment: DEV — using logback-dev.xml");
        } else {
            System.setProperty("logback.configurationFile", "src/main/resources/logback.xml");
            System.out.println("[KeyProvider] Environment: PROD — using logback.xml");
        }

        // Rebind logger to ensure correct configuration
        logger = LoggerFactory.getLogger(KeyProvider.class);
        logger.info("Environment initialized: {}", activeEnv);

        initialized = true;
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
                logger.warn("Unable to read encryption key from .env file.", e);
            }
        }

        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    """
                    Encryption key not found.
                    Set it using one of these methods:
                      - Environment variable: CONFIG_MASTER_KEY
                      - .env file: CONFIG_MASTER_KEY=YourStrongKey
                      - Secure file: use getKeyFromFile(path)
                    """
            );
        }

        return key.trim();
    }

    /** Reads key from file (for containers or secrets mounts) */
    public static String getKeyFromFile(String filePath) throws IOException {
        if (!initialized) init();
        Path path = Paths.get(filePath);
        String key = Files.readString(path).trim();
        logger.info("Loaded encryption key from secure file: {}", path);
        return key;
    }

    /** Convenience method for use elsewhere */
    public static boolean isDev() {
        if (!initialized) init();
        return "DEV".equalsIgnoreCase(activeEnv);
    }

    public static boolean isProd() {
        return !isDev();
    }

    public static String getEnvironment() {
        if (!initialized) init();
        return activeEnv;
    }
}
