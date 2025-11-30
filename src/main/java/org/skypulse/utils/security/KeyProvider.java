package org.skypulse.utils.security;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeyProvider = Central secret manager for SkyPulse.
 * Responsibilities:
 *   1. Load environment (.env + system ENV)
 *   2. Initialize correct Logback (dev/prod)
 *   3. Provide ANY secret (JWT, API keys, DB overrides)
 *   4. Provide encryption key for SecureFieldCrypto
 *   5. Provide frontend base URL
 * Priority for secret resolution:
 *     1. System environment variable
 *     2. .env file
 */
public class KeyProvider {

    private static final Logger logger = LoggerFactory.getLogger(KeyProvider.class);

    private static final String ENV_MASTER_KEY      = "CONFIG_MASTER_KEY";
    private static final String ENV_ENVIRONMENT     = "APP_ENV";
    private static final String ENV_FRONTEND_BASE_URL = "FRONTEND_BASE_URL";

    private static volatile boolean initialized = false;
    private static String activeEnv = "PRODUCTION";
    private static Dotenv dotenv;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private static synchronized void init() {
        if (initialized) return;

        try {
            dotenv = Dotenv.configure()
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();

            String env = System.getenv(ENV_ENVIRONMENT);
            if (env == null || env.isBlank()) {
                env = dotenv.get(ENV_ENVIRONMENT, "PRODUCTION");
            }
            activeEnv = env.toUpperCase();
            System.setProperty(ENV_ENVIRONMENT, activeEnv);

            if ("DEVELOPMENT".equals(activeEnv)) {
                loadLogback("logback-dev.xml");
            } else {
                loadLogback("logback.xml");
            }

            initialized = true;
            logger.info("KeyProvider initialized — Environment: {}", activeEnv);

        } catch (Exception e) {
            System.err.println("KeyProvider initialization failed: " + e.getMessage());
            throw new IllegalStateException("Failed initializing security environment", e);
        }
    }

    public static String get(String keyName) {
        if (!initialized) init();

        if (CACHE.containsKey(keyName)) return CACHE.get(keyName);

        String value = System.getenv(keyName);
        if ((value == null || value.isBlank()) && dotenv != null) {
            value = dotenv.get(keyName);
        }

        if (value == null || value.isBlank()) {
            logger.error("Missing required secret '{}'", keyName);
            throw new IllegalStateException(
                    "\nMissing secret: " + keyName +
                            "\nProvide it via:\n" +
                            "  • System ENV: " + keyName + "\n" +
                            "  • OR .env file: " + keyName + "=value\n"
            );
        }

        CACHE.put(keyName, value.trim());
        logger.debug("Secret '{}' loaded ({} chars)", keyName, value.length());
        return value.trim();
    }

    /** Encryption master key */
    public static String getEncryptionKey() {
        return get(ENV_MASTER_KEY);
    }

    /** Frontend base URL */
    public static String getFrontendBaseUrl() {
        if (!initialized) init();

        String value = System.getenv(ENV_FRONTEND_BASE_URL);
        if ((value == null || value.isBlank()) && dotenv != null) {
            value = dotenv.get(ENV_FRONTEND_BASE_URL);
        }

        if (value == null || value.isBlank()) {
            logger.warn("FRONTEND_BASE_URL not set, defaulting to http://localhost:5173");
            value = "http://localhost:5173";
        }

        return value.trim();
    }

    /** Load secret from file */
    public static String getKeyFromFile(String filePath) {
        if (!initialized) init();

        try {
            Path path = Paths.get(filePath);
            String key = Files.readString(path).trim();
            if (key.isEmpty()) throw new IllegalStateException("Secure key file is empty: " + filePath);
            logger.info("Loaded secret from secure file: {}", filePath);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading secure key from file: " + filePath, e);
        }
    }

    public static boolean isDev() { return "DEVELOPMENT".equalsIgnoreCase(activeEnv); }
    public static boolean isProd() { return !"DEVELOPMENT".equalsIgnoreCase(activeEnv); }
    public static String getEnvironment() { if (!initialized) init(); return activeEnv; }

    /** Load logback safely */
    private static void loadLogback(String fileName) {
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.reset();

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(ctx);
            configurator.doConfigure(KeyProvider.class.getClassLoader().getResourceAsStream(fileName));

            StatusPrinter.printInCaseOfErrorsOrWarnings(ctx);
        } catch (Exception e) {
            System.err.println("ERROR loading logback: " + fileName + " → " + e.getMessage());
        }
    }
}
