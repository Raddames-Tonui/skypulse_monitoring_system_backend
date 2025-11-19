package org.skypulse.utils;


import org.skypulse.config.database.DatabaseManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class JdbcUtils {
    private JdbcUtils() {}

    public static Connection getConnection() throws SQLException {
        DataSource ds = DatabaseManager.getDataSource();
        assert ds != null;
        return ds.getConnection();
    }
}
