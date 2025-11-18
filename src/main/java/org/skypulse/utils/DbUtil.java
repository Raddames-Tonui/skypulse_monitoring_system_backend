package org.skypulse.utils;

import org.skypulse.config.database.DatabaseManager;

import java.sql.PreparedStatement;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Provide executeQuery, executeUpdate, executeBatch wrappers
 * Automatically handle connection management
 *Long userId = DbUtil.querySingle(
 *     "SELECT user_id FROM users WHERE user_email = ?",
 *     ps -> ps.setString(1, email),
 *     rs -> rs.getLong("user_id"));

 * DbUtil.update("UPDATE users SET is_active = TRUE WHERE user_id = ?",
 *     ps -> ps.setLong(1, userId));
 * */
public class DbUtil {

    private DbUtil() {}

    public static <T> T querySingle(String sql, SqlConsumer<PreparedStatement> paramSetter, SqlFunction<ResultSet, T> resultMapper) throws SQLException {
        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
                paramSetter.accept(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? resultMapper.apply(rs) : null;
                }
        }
    }

    public static int update(String sql, SqlConsumer<PreparedStatement> paramSetter) throws SQLException {{
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
                PreparedStatement ps  = conn.prepareStatement(sql)) {
                    paramSetter.accept(ps);
                    return ps.executeUpdate();
            }

        }
    }

    public static void batch(String sql, SqlConsumer<PreparedStatement> batchSetter) throws SQLException {
        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            batchSetter.accept(ps);
            ps.executeBatch();
        }
    }


    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}


/**

 * */
