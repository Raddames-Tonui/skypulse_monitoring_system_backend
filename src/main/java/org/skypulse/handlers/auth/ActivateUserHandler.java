package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.CookieUtil;
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

/***
 * Activates user using token, deletes token, and creates session for the user
 * */
public class ActivateUserHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivateUserHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        Map<String, Object> input;
        try {
            input = mapper.readValue(exchange.getInputStream(), Map.class);
            if (input == null) {
                ResponseUtil.sendError(exchange, 400, "Invalid JSON payload");
                return;
            }
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 400, "Invalid request body");
            return;
        }

        String token = (input.get("token") instanceof String) ? (String) input.get("token") : null;
        String password = (input.get("password") instanceof String) ? (String) input.get("password") : null;

        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "Token and password are required");
            return;
        }
        if (password.length() < 6) {
            ResponseUtil.sendError(exchange, 400, "Password must be at least 6 characters");
            return;
        }

        try (Connection conn = JdbcUtils.getConnection()) {
            conn.setAutoCommit(false);

            String sql = """
                        WITH token_check AS (
                            SELECT token_id, user_id, expires_at
                            FROM user_password_tokens
                            WHERE token = ? FOR UPDATE
                        ),
                            updated_user AS (
                                UPDATE users u
                                SET password_hash = ?, is_active = true, date_modified = now()
                                FROM token_check t 
                                WHERE u.user_id = t.user_id
                                       AND t.expires_at > now()
                                RETURNING u.user_id, u.uuid, u.first_name, u.last_name, u.user_email, u.role_id                                       
                            ),
                            delete_token AS (
                                DELETE FROM user_password_tokens t
                                USING updated_user u
                                WHERE t.user_id = u.user_id
                                       AND t.token = ?
                                       AND t.expires_at > now()
                                RETURNING 1
                            ),
                            user_role AS (
                                SELECT r.role_name
                                FROM roles r
                                JOIN updated_user u ON u.role_id = r.role_id
                            )
                            SELECT u.user_id, u.uuid, u.first_name, u.last_name, u.user_email, ur.role_name
                            FROM updated_user u
                            CROSS JOIN user_role ur
                        """;

            long userId;
            String userUuid, firstName, lastName, email, roleName;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, token);  // for token_check
                ps.setString(2, PasswordUtil.hashPassword(password));
                ps.setString(3, token);  // for delete_token

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        ResponseUtil.sendError(exchange, 400, "Invalid or expired token");
                        return;
                    }
                    userId = rs.getLong("user_id");
                    userUuid = rs.getString("uuid");
                    firstName = rs.getString("first_name");
                    lastName = rs.getString("last_name");
                    email = rs.getString("user_email");
                    roleName = rs.getString("role_name");
                }
            }

            Instant now = Instant.now();
            String refreshToken = TokenUtil.generateToken();
            String refreshHash = TokenUtil.hashToken(refreshToken);
            UUID jwtId;

            long REFRESH_TOKEN_TTL = 30L * 24 * 3600;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auth_sessions (user_id, refresh_token_hash, jwt_id, issued_at, expires_at, last_used_at, login_time, session_status) " +
                            "VALUES (?, ?, uuid_generate_v4(), ?, ?, ?, ?, 'active') RETURNING jwt_id"
            )) {
                ps.setLong(1, userId);
                ps.setString(2, refreshHash);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setTimestamp(4, Timestamp.from(now.plusSeconds(REFRESH_TOKEN_TTL)));
                ps.setTimestamp(5, Timestamp.from(now));
                ps.setTimestamp(6, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        ResponseUtil.sendError(exchange, 500, "Failed to create session");
                        return;
                    }
                    jwtId = (UUID) rs.getObject("jwt_id");
                }
            }

            boolean isSecure = exchange.getRequestScheme().equalsIgnoreCase("https");
            long ACCESS_TOKEN_TTL = 15 * 60;
            CookieUtil.setAccessTokenCookie(exchange, jwtId, UUID.fromString(userUuid), email, roleName, ACCESS_TOKEN_TTL, isSecure);
            CookieUtil.setRefreshTokenCookie(exchange, refreshToken, REFRESH_TOKEN_TTL, isSecure);

            conn.commit();

            Map<String, Object> profile = new HashMap<>();
            profile.put("uuid", userUuid);
            profile.put("full_name", (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : ""));
            profile.put("email", email);
            profile.put("role_name", roleName);

            ResponseUtil.sendSuccess(exchange, "Account activated successfully", Map.of("user", profile));
            logger.info("User {} activated", userId);

        } catch (Exception e) {
            logger.error("Activation failed", e);
            ResponseUtil.sendError(exchange, 500, "Activation failed");
        }
    }
}
