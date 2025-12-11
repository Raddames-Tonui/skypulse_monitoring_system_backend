package org.skypulse.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.skypulse.config.database.DatabaseManager;

import java.sql.PreparedStatement;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Objects;

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



    public static int update(String sql, SqlConsumer<PreparedStatement> paramSetter) throws SQLException {{
            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
                PreparedStatement ps  = conn.prepareStatement(sql)) {
                    paramSetter.accept(ps);
                    return ps.executeUpdate();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

    }
    }



    public static Connection getConnection() throws SQLException {
        DataSource ds = DatabaseManager.getDataSource();
        if (ds == null) throw new SQLException("DataSource is null");
        return ds.getConnection();
    }



    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException, JsonProcessingException;
    }



    public static int setParams(PreparedStatement st, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            st.setObject(i++, p);
        }
        return i;
    }
}
