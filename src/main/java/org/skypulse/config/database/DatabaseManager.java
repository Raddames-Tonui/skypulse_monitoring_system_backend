package org.skypulse.config.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.config.utils.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Handles database connectivity using HikariCP connection pooling.
 * Fully supports degraded-mode startup and background reconnection.
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private static volatile HikariDataSource dataSource;
    private static volatile boolean initialized = false;

    public static boolean isInitialized() {
        return initialized;
    }

    public static synchronized void initialize(XmlConfiguration cfg) throws SQLException {
        LogContext.start("DatabaseManager");

        if (cfg == null || cfg.dataSource == null || cfg.connectionPool == null) {
            initialized = false;
            throw new SQLException("Invalid configuration: missing dataSource or connectionPool section.");
        }

        try {
            logger.info("Initializing database connection...");
            HikariConfig hc = getHikariConfig(cfg);

            // SSL settings
            if (cfg.dataSource.encrypt) {
                hc.addDataSourceProperty("ssl", "true");
                hc.addDataSourceProperty("sslmode", "require");
                logger.info("SSL enabled for PostgreSQL connection");
            } else {
                hc.addDataSourceProperty("ssl", "false");
                logger.info("SSL disabled for PostgreSQL connection");
            }

            // Build pool
            HikariDataSource newDs = new HikariDataSource(hc);

            logger.debug("Testing initial database connection...");
            try (Connection conn = newDs.getConnection()) {
                if (!conn.isValid(2)) {
                    newDs.close();
                    throw new SQLException("Connection failed: connection is invalid.");
                }
            }

            if (dataSource != null) {
                try { dataSource.close(); } catch (Exception ignored) {}
            }

            dataSource = newDs;
            initialized = true;
            logger.info("Database connection successful!");

        } catch (Exception e) {
            initialized = false;
            logger.error("Database initialization failed: {}", e.getMessage());
            shutdown();
            throw new SQLException(e);
        } finally {
            LogContext.clear();
        }
    }

    @NotNull
    private static HikariConfig getHikariConfig(XmlConfiguration cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.dataSource.jdbcUrl);
        hc.setUsername(cfg.dataSource.username);
        hc.setPassword(cfg.dataSource.password);
        hc.setDriverClassName(
                cfg.dataSource.driverClassName != null
                        ? cfg.dataSource.driverClassName
                        : "org.postgresql.Driver"
        );
        hc.setMaximumPoolSize(cfg.connectionPool.maximumPoolSize);
        hc.setMinimumIdle(cfg.connectionPool.minimumIdle);
        hc.setIdleTimeout(cfg.connectionPool.idleTimeout);
        hc.setConnectionTimeout(cfg.connectionPool.connectionTimeout);
        hc.setMaxLifetime(cfg.connectionPool.maxLifetime);
        return hc;
    }


    public static HikariDataSource getDataSource() {
        return initialized ? dataSource : null;
    }

    /**
     * Shutdown the connection pool safely.
     */
    public static synchronized void shutdown() {
        initialized = false;
        if (dataSource != null) {
            try {
                dataSource.close();
                logger.info("Database connection pool shutdown successfully.");
            } catch (Exception e) {
                logger.warn("Error shutting down connection pool: {}", e.getMessage());
            }
        }
    }
}
