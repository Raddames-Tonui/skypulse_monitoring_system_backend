package org.skypulse.config.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.utils.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;


/**
 * Handles database connectivity using HikariCP connection pooling.
 * Automatically toggles SSL based on configuration.
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static HikariDataSource dataSource;

    /**
     * Initializes the HikariCP connection pool from the Configuration.
     * @param cfg Configuration object loaded from XML.
     * @throws SQLException if the database connection fails.
     */
    public static void initialize(XmlConfiguration cfg) throws SQLException {
        LogContext.start("DatabaseManager");
        if (cfg == null || cfg.dataSource == null || cfg.connectionPool == null) {
            logger.error("Invalid configuration: missing dataSource or connectionPool section.");
            throw new SQLException("Invalid configuration: missing dataSource or connectionPool section.");
        }

        try {
            logger.info("Initializing database connection...");
            HikariConfig hc = getHikariConfig(cfg);

            // --- Conditional SSL Configuration ---
            if (cfg.dataSource.encrypt) {
                hc.addDataSourceProperty("ssl", "true");
                hc.addDataSourceProperty("sslmode", "require");
                logger.info("SSL enabled for PostgreSQL connection");
            } else {
                hc.addDataSourceProperty("ssl", "false");
                logger.info("SSL disabled for PostgreSQL connection");
            }


            // --- Create and test pool ---
            dataSource = new HikariDataSource(hc);

            logger.debug("Testing initial database connection...");
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(2)){
                    logger.info("Database connection successful!");
                } else {
                    throw new SQLException("Connection failed: connection is invalid.");
                }
            }
        }catch (SQLException e){
            logger.error("Database initialization failed: {}", e.getMessage());
            shutdown();
            throw e; // propagate to main
        } catch (Exception e){
            logger.error("Unexpected error during DB initialization: ", e);
            throw new SQLException("Unexpected error during DB initialization: " + e);
        } finally {
            LogContext.clear();
        }
    }

    @NotNull
    private static HikariConfig getHikariConfig(XmlConfiguration cfg) {
        HikariConfig hc = new HikariConfig();

        // --- JDBC settings ---
        hc.setJdbcUrl(cfg.dataSource.jdbcUrl);
        hc.setUsername(cfg.dataSource.username);
        hc.setPassword(cfg.dataSource.password);
        hc.setDriverClassName(
                cfg.dataSource.driverClassName != null
                        ? cfg.dataSource.driverClassName
                        : "org.postgresql.Driver"
        );

        // --- Connection Pool Settings ---
        hc.setMaximumPoolSize(cfg.connectionPool.maximumPoolSize);
        hc.setMinimumIdle(cfg.connectionPool.minimumIdle);
        hc.setIdleTimeout(cfg.connectionPool.idleTimeout);
        hc.setConnectionTimeout(cfg.connectionPool.connectionTimeout);
        hc.setMaxLifetime(cfg.connectionPool.maxLifetime);
        return hc;
    }

    /**
     * Shutdown the Connection Pool
     * */
    public static void shutdown(){
        if (dataSource != null){
            try {
                dataSource.close();
                logger.info("Database connection pool shutdown successfully.");
            } catch (Exception e) {
                logger.warn("Error shutting down connection pool: {}", e.getMessage());
            }
        }
    }

}