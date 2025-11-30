package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.PasswordUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ResetPasswordHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ResetPasswordHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();
    private final long REFRESH_TOKEN_TTL = 30L * 24 * 3600; // 30 days

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

            // 1. Validate reset token
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

            // 2. Update password
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

            // 3. Mark token used
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user_password_tokens SET is_used = TRUE WHERE token = ?"
            )) {
                ps.setString(1, token);
                ps.executeUpdate();
            }

            // 4. Get email
            String email;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_email FROM users WHERE user_id = ?"
            )) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString("user_email") == null) {
                        conn.rollback();
                        ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                                "Could not retrieve user email");
                        return;
                    }
                    email = rs.getString("user_email");
                }
            }

            // 5. Ensure primary email
            boolean emailExists = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM user_contacts WHERE user_id = ? AND type='EMAIL' AND value = ?"
            )) {
                ps.setLong(1, userId);
                ps.setString(2, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) emailExists = true;
                }
            }

            if (!emailExists) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO user_contacts (user_id, type, value, is_primary, verified, date_created) " +
                                "VALUES (?, 'EMAIL', ?, TRUE, TRUE, NOW())"
                )) {
                    ps.setLong(1, userId);
                    ps.setString(2, email);
                    ps.executeUpdate();
                } catch (Exception e) {
                    logger.error("Failed to insert primary email for user {}", userId, e);
                    ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                            "Could not add primary email. Please contact support.");
                    return;
                }
            }

            // 6. Create refresh token session
            try {
                Instant now = Instant.now();
                String refreshToken = TokenUtil.generateToken();
                String refreshHash = TokenUtil.hashToken(refreshToken);

                if (refreshHash != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO auth_sessions (user_id, refresh_token_hash, jwt_id, issued_at, expires_at, last_used_at, login_time, session_status) " +
                                    "VALUES (?, ?, uuid_generate_v4(), ?, ?, ?, ?, 'active')"
                    )) {
                        ps.setLong(1, userId);
                        ps.setString(2, refreshHash);
                        ps.setTimestamp(3, Timestamp.from(now));
                        ps.setTimestamp(4, Timestamp.from(now.plusSeconds(REFRESH_TOKEN_TTL)));
                        ps.setTimestamp(5, Timestamp.from(now));
                        ps.setTimestamp(6, Timestamp.from(now));
                        ps.executeUpdate();
                    }

                    CookieImpl cookie = new CookieImpl("refreshToken", refreshToken);
                    cookie.setHttpOnly(true);
                    cookie.setSecure(true);
                    cookie.setPath("/");
                    cookie.setMaxAge((int) REFRESH_TOKEN_TTL);
                    exchange.setResponseCookie(cookie);
                }
            } catch (Exception e) {
                logger.warn("Failed creating refresh token session after password reset for user {}", userId, e);
            }

            conn.commit();

            // 7. Build user profile
            String userUuid = null, firstName = null, lastName = null, roleName = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, first_name, last_name, r.role_name FROM users u LEFT JOIN roles r ON u.role_id = r.role_id WHERE user_id = ?"
            )) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userUuid = rs.getString("uuid");
                        firstName = rs.getString("first_name");
                        lastName = rs.getString("last_name");
                        roleName = rs.getString("role_name");
                    }
                }
            }

            Map<String, Object> profile = new HashMap<>();
            profile.put("uuid", userUuid != null ? userUuid : UUID.randomUUID().toString());
            profile.put("full_name", (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : ""));
            profile.put("email", email);
            profile.put("role_name", roleName);

            ResponseUtil.sendSuccess(exchange, "Password reset successful", Map.of("user", profile));
            logger.info("User {} password reset and logged in", userId);

        } catch (Exception e) {
            logger.error("Password reset failed", e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Password reset failed");
        }
    }
}
