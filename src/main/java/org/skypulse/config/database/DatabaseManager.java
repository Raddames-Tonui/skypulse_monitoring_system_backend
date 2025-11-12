package org.skypulse.config.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.skypulse.config.Configuration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Handles database connectivity using HikariCP connection pooling.
 * Automatically toggles SSL based on configuration.
 */
public class DatabaseManager {

    private static HikariDataSource dataSource;

    /**
     * Initializes the HikariCP connection pool from the Configuration.
     *
     * @param cfg Configuration object loaded from XML.
     * @throws SQLException if the database connection fails.
     */
    public static void initialize(Configuration cfg) throws SQLException {
        if (cfg == null || cfg.dataSource == null || cfg.connectionPool == null) {
            throw new IllegalArgumentException("Invalid configuration: missing dataSource or connectionPool section.");
        }

        HikariConfig hc = new HikariConfig();

        // --- Core JDBC settings ---
        hc.setJdbcUrl(cfg.dataSource.jdbcUrl);
        hc.setUsername(cfg.dataSource.user);
        hc.setPassword(cfg.dataSource.password);
        hc.setDriverClassName(cfg.dataSource.driverClassName != null
                ? cfg.dataSource.driverClassName
                : "org.postgresql.Driver");

        // --- Connection Pool settings ---
        hc.setMaximumPoolSize(cfg.connectionPool.maximumPoolSize);
        hc.setMinimumIdle(cfg.connectionPool.minimumIdle);
        hc.setIdleTimeout(cfg.connectionPool.idleTimeout);
        hc.setConnectionTimeout(cfg.connectionPool.connectionTimeout);
        hc.setMaxLifetime(cfg.connectionPool.maxLifetime);

        // --- Conditional SSL Configuration ---
        if (cfg.dataSource.encrypt) {
            hc.addDataSourceProperty("ssl", "true");
            hc.addDataSourceProperty("sslmode", "require");
            System.out.println("SSL enabled for PostgreSQL connection.");
        } else {
            hc.addDataSourceProperty("ssl", "false");
            System.out.println("SSL disabled for PostgreSQL connection.");
        }

        // --- Create and test pool ---
        dataSource = new HikariDataSource(hc);

        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                System.out.println("Database connection successful!");
            } else {
                throw new SQLException("Database connection test failed.");
            }
        } catch (SQLException e) {
            shutdown();
            throw e;
        }
    }

    /**
     * Returns the active HikariCP DataSource.
     * @return HikariDataSource instance.
     */
    public static HikariDataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException("Database not initialized. Call initialize() first.");
        }
        return dataSource;
    }

    /**
     * Gracefully closes the HikariCP connection pool.
     */
    public static void shutdown() {
        if (dataSource != null) {
            try {
                dataSource.close();
                System.out.println("Database connection pool shut down.");
            } catch (Exception e) {
                System.err.println("Error while shutting down DB pool: " + e.getMessage());
            }
        }
    }
}