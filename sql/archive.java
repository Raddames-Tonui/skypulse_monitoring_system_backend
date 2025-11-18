package org.skypulse.utils;

import org.skypulse.config.database.DatabaseManager;

import javax.sql.DataSource;
import java.sql.*;
import java.util.function.Consumer;

public final class DbUtil {

    private DbUtil() {}

    public static <T> T querySingle(String sql, SqlConsumer<PreparedStatement> paramSetter, SqlFunction<ResultSet, T> resultMapper) throws SQLException {
        try (Connection conn = DatabaseManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            paramSetter.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? resultMapper.apply(rs) : null;
            }
        }
    }

    public static int update(String sql, SqlConsumer<PreparedStatement> paramSetter) throws SQLException {
        try (Connection conn = DatabaseManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            paramSetter.accept(ps);
            return ps.executeUpdate();
        }
    }

    public static void batch(String sql, SqlConsumer<PreparedStatement> batchSetter) throws SQLException {
        try (Connection conn = DatabaseManager.getDataSource().getConnection();
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
