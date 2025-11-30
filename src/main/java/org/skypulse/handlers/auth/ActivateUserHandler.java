package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.JwtUtil;
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

public class ActivateUserHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivateUserHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();
    private final long ACCESS_TOKEN_TTL = 15 * 60;        // 15 minutes
    private final long REFRESH_TOKEN_TTL = 30L * 24 * 3600; // 30 days

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        Map<String, Object> input;
        try {
            input = mapper.readValue(exchange.getInputStream(), Map.class);
            if (input == null) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON");
                return;
            }
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid payload");
            return;
        }

        String token = (input.get("token") instanceof String) ? (String) input.get("token") : null;
        String password = (input.get("password") instanceof String) ? (String) input.get("password") : null;

        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Token and password are required");
            return;
        }

        if (password.length() < 6) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Password must be at least 6 characters");
            return;
        }

        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);
            long userId;

            // Validate token
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT token_id, user_id, is_used, expires_at FROM user_password_tokens WHERE token = ? FOR UPDATE"
            )) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid token");
                        return;
                    }
                    if (rs.getBoolean("is_used")) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Token has already been used");
                        return;
                    }
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    if (expiresAt.toInstant().isBefore(Instant.now())) {
                        ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Token expired");
                        return;
                    }
                    userId = rs.getLong("user_id");
                }
            }

            // Hash and set password, activate user
            String hashedPassword = PasswordUtil.hashPassword(password);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, is_active = TRUE, date_modified = NOW() WHERE user_id = ?"
            )) {
                ps.setString(1, hashedPassword);
                ps.setLong(2, userId);
                ps.executeUpdate();
            }

            // Mark token as used
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user_password_tokens SET is_used = TRUE WHERE token = ?"
            )) {
                ps.setString(1, token);
                ps.executeUpdate();
            }

            // Verify primary email
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE user_contacts SET verified = TRUE WHERE user_id = ? AND is_primary = TRUE AND type='email'"
            )) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }

            // Fetch user details
            String userUuid = null, email = null, roleName = null, firstName = null, lastName = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, user_email, first_name, last_name, r.role_name FROM users u LEFT JOIN roles r ON u.role_id = r.role_id WHERE user_id = ?"
            )) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userUuid = rs.getString("uuid");
                        email = rs.getString("user_email");
                        firstName = rs.getString("first_name");
                        lastName = rs.getString("last_name");
                        roleName = rs.getString("role_name");
                    }
                }
            }

            if (userUuid == null || email == null) {
                conn.rollback();
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to fetch user details");
                return;
            }

            // Create refresh token safely
            try {
                Instant now = Instant.now();
                String refreshToken = TokenUtil.generateToken();
                String refreshHash = TokenUtil.hashToken(refreshToken);

                if (refreshHash != null) { // only insert if hash generated
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
                logger.warn("Failed to create refresh token session, but activation succeeded", e);
            }

            conn.commit();

            Map<String, Object> profile = new HashMap<>();
            profile.put("uuid", userUuid);
            profile.put("full_name", firstName + " " + lastName);
            profile.put("email", email);
            profile.put("role_name", roleName);

            ResponseUtil.sendSuccess(exchange, "Account activated successfully", Map.of("user", profile));
            logger.info("User {} activated", userId);

        } catch (Exception e) {
            logger.error("Activation failed", e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Activation failed");
        }
    }
}
