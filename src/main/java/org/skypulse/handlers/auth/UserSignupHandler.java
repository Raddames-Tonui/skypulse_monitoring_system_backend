package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.config.security.PasswordUtil;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

public class UserSignupHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        try (InputStream is = exchange.getInputStream()) {
            Map<String, Object> body = JsonUtil.mapper().readValue(is, Map.class);

            String firstName = (body.get("first_name") != null) ? body.get("first_name").toString() : null;
            String lastName  = (body.get("last_name") != null) ? body.get("last_name").toString() : null;
            String email     = (body.get("user_email") != null) ? body.get("user_email").toString() : null;
            String password  = (body.get("password") != null) ? body.get("password").toString() : null;

            // Safely handle role_id as Integer or String
            int roleId;
            Object roleObj = body.get("role_id");
            if (roleObj instanceof Number) {
                roleId = ((Number) roleObj).intValue();
            } else if (roleObj != null) {
                roleId = Integer.parseInt(roleObj.toString());
            } else {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing role_id");
                return;
            }

            if (firstName == null || lastName == null || email == null || password == null) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required fields");
                return;
            }

            DataSource ds = DatabaseManager.getDataSource();
            if (ds == null) {
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Database not initialized");
                return;
            }

            try (Connection conn = ds.getConnection()) {
                conn.setAutoCommit(false);

                String checkEmailSql = "SELECT 1 FROM users WHERE user_email = ?";
                try (PreparedStatement ps = conn.prepareStatement(checkEmailSql)) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            ResponseUtil.sendError(exchange, StatusCodes.CONFLICT, "Email already exists");
                            return;
                        }
                    }
                }

                // Insert user
                String insertUserSql = """
                    INSERT INTO users (first_name, last_name, user_email, password_hash, role_id)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING user_id;
                    """;

                long userId;
                try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                    ps.setString(1, firstName);
                    ps.setString(2, lastName);
                    ps.setString(3, email);
                    ps.setString(4, PasswordUtil.hashPassword(password));
                    ps.setInt(5, roleId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getLong(1);
                        } else {
                            conn.rollback();
                            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to create user");
                            return;
                        }
                    }
                }

                // Insert default preferences
                String insertPrefsSql = "INSERT INTO user_preferences (user_id) VALUES (?);";
                try (PreparedStatement ps = conn.prepareStatement(insertPrefsSql)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }

                conn.commit();
                ResponseUtil.sendCreated(exchange, "User created successfully", null);

            } catch (Exception e) {
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Signup failed: " + e.getMessage());
            }
        }
    }
}
