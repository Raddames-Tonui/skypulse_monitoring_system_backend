package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ResetPasswordHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ResetPasswordHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        Map<String, Object> input;

        try {
            input = mapper.readValue(exchange.getInputStream(), Map.class);
            if (input == null) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON payload");
                return;
            }
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid request body");
            return;
        }

        String token = (input.get("token") instanceof String) ? (String) input.get("token") : null;
        String newPassword = (input.get("password") instanceof String) ? (String) input.get("password") : null;

        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Token and password are required");
            return;
        }
        if (newPassword.length() < 6) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Password must be at least 6 characters");
            return;
        }

        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);
            long userId;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT token_id, user_id, is_used, expires_at FROM user_password_tokens WHERE token = ? FOR UPDATE"
            )) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid reset token");
                        return;
                    }
                    if (rs.getBoolean("is_used")) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Token has already been used");
                        return;
                    }
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    if (expiresAt.toInstant().isBefore(Instant.now())) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Reset token expired");
                        return;
                    }
                    userId = rs.getLong("user_id");
                }
            }

            String hashedPassword = PasswordUtil.hashPassword(newPassword);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, date_modified = NOW() WHERE user_id = ?"
            )) {
                ps.setString(1, hashedPassword);
                ps.setLong(2, userId);
                if (ps.executeUpdate() == 0) {
                    ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "User not found");
                    return;
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user_password_tokens SET is_used = TRUE WHERE token = ?"
            )) {
                ps.setString(1, token);
                ps.executeUpdate();
            }

            conn.commit();

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Password reset successful");
            ResponseUtil.sendSuccess(exchange, "Password reset successful", response);

            logger.info("User {} password reset successfully", userId);

        } catch (Exception e) {
            logger.error("Password reset failed", e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Password reset failed");
        }
    }
}
