package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserLoginRequest;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.rest.auth.AuthMiddleware;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.PasswordUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class UserLoginHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(UserLoginHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();
    private final long ACCESS_TOKEN_TTL;
    private final long REFRESH_TOKEN_TTL;

    public UserLoginHandler(XmlConfiguration cfg) {
        long accessToken = 15 * 60;        // default 15 min
        long refreshToken = 30 * 24 * 3600; // default 30 days

        try { accessToken = Long.parseLong(cfg.jwtConfig.accessToken) * 60; } catch (Exception ignored) {}
        try { refreshToken = Long.parseLong(cfg.jwtConfig.refreshToken) * 24 * 3600; } catch (Exception ignored) {}

        this.ACCESS_TOKEN_TTL = accessToken;
        this.REFRESH_TOKEN_TTL = refreshToken;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        UserLoginRequest req = mapper.readValue(exchange.getInputStream(), UserLoginRequest.class);

        if (req.email == null || req.email.isBlank() ||
                req.password == null || req.password.isBlank()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required fields: email and password");
            return;
        }

        String ipAddress = exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "unknown";
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String deviceName = "unknown";

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            String selectUser = """
                SELECT user_id, uuid, password_hash, first_name, last_name, user_email, is_deleted, role_id
                FROM users
                WHERE user_email = ?
            """;

            long userId;
            UUID userUuid;
            String passwordHash, firstName, lastName, email;
            Integer roleId;

            try (PreparedStatement ps = conn.prepareStatement(selectUser)) {
                ps.setString(1, req.email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        logLoginFailure(conn, req.email, ipAddress, userAgent, "Email not found");
                        ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "Please check your credentials");
                        return;
                    }

                    userId = rs.getLong("user_id");
                    userUuid = UUID.fromString(rs.getObject("uuid").toString());
                    passwordHash = rs.getString("password_hash");
                    email = rs.getString("user_email");
                    firstName = rs.getString("first_name");
                    lastName = rs.getString("last_name");
                    roleId = rs.getObject("role_id") == null ? null : rs.getInt("role_id");

                    if (rs.getBoolean("is_deleted")) {
                        logLoginFailure(conn, email, ipAddress, userAgent, "Account deleted");
                        ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "Please contact administrator.");
                        return;
                    }
                }
            }

            if (!PasswordUtil.verifyPassword(req.password, passwordHash)) {
                logLoginFailure(conn, email, ipAddress, userAgent, "Invalid credentials");
                ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "Invalid credentials");
                return;
            }


            String roleName = "";
            if (roleId != null) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT role_name FROM roles WHERE role_id = ?")) {
                    ps.setInt(1, roleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) roleName = rs.getString("role_name");
                    }
                }
            }

            Instant now = Instant.now();

            String refreshToken = TokenUtil.generateToken();
            String refreshHash = TokenUtil.hashToken(refreshToken);

            String insertAuth = """
                INSERT INTO auth_sessions
                (user_id, refresh_token_hash, jwt_id, issued_at, expires_at, last_used_at, login_time, ip_address, user_agent, device_name, session_status)
                VALUES (?, ?, uuid_generate_v4(), ?, ?, ?, ?, ?, ?, ?, 'active')
                RETURNING jwt_id, auth_session_id
            """;

            UUID jwtId;
            try (PreparedStatement ps = conn.prepareStatement(insertAuth)) {
                ps.setLong(1, userId);
                ps.setString(2, refreshHash);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setTimestamp(4, Timestamp.from(now.plusSeconds(REFRESH_TOKEN_TTL)));
                ps.setTimestamp(5, Timestamp.from(now));
                ps.setTimestamp(6, Timestamp.from(now));
                ps.setString(7, ipAddress);
                ps.setString(8, userAgent);
                ps.setString(9, deviceName);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Failed to create auth_session");
                    jwtId = (UUID) rs.getObject("jwt_id");
                }
            }

            AuthMiddleware.getUserByUuid(exchange, jwtId, userUuid, email, roleName, ACCESS_TOKEN_TTL);

            CookieImpl refreshCookie = new CookieImpl("refreshToken", refreshToken);
            refreshCookie.setHttpOnly(true);
            refreshCookie.setSecure(true);
            refreshCookie.setPath("/");
            refreshCookie.setMaxAge((int) REFRESH_TOKEN_TTL);
            exchange.setResponseCookie(refreshCookie);

            Map<String, Object> profile = new HashMap<>();
            profile.put("uuid", userUuid);
            profile.put("full_name", firstName + " " + lastName);
            profile.put("email", email);
            profile.put("role_name", roleName);
            profile.put("company_name", null);
            profile.put("user_contacts", Collections.emptyList());
            profile.put("user_preferences", Map.of(
                    "alert_channel", "email",
                    "receive_weekly_reports", true,
                    "timezone", "UTC",
                    "language", "en",
                    "dashboard_layout", Map.of("type", "jsonb", "value", "{}", "null", false)
            ));

            Map<String, Object> data = Map.of("user", profile);

            ResponseUtil.sendSuccess(exchange, "Login successful", data);

        } catch (Exception e) {
            logger.error("Login failed", e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }

    private void logLoginFailure(Connection conn, String email, String ip, String ua, String reason) {
        String sql = """
            INSERT INTO login_failures
            (user_email, ip_address, user_agent, reason)
            VALUES (?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, ip);
            ps.setString(3, ua);
            ps.setString(4, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to log login failure for {}: {}", email, e.getMessage());
        }
    }
}
