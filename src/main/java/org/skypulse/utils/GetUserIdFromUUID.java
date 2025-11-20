package org.skypulse.utils;

import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

public final class GetUserIdFromUUID {


    private GetUserIdFromUUID() {}


    public static long getUserIdFromUuid(String userUuidStr) {
        try (Connection conn = JdbcUtils.getConnection()) {
            String sql = "SELECT user_id FROM users WHERE uuid = ? AND is_active = TRUE";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, UUID.fromString(userUuidStr));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("user_id");
                    } else {
                        throw new IllegalArgumentException("User not found or inactive for UUID: " + userUuidStr);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get user_id for UUID: " + userUuidStr, e);
        }
    }
}
