package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.Headers;
import org.skypulse.handlers.auth.dto.UserLoginRequest;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.security.PasswordUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class UserLoginHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(UserLoginHandler.class);
    private static final long ACCESS_TOKEN_TTL = 3600L;
    private static final long REFRESH_TOKEN_TTL = 30L * 24L * 3600L; // 30 days
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        UserLoginRequest req = mapper.readValue(exchange.getInputStream(), UserLoginRequest.class);

        if (exchange.getRequestMethod().equalToString("OPTIONS")) {
            exchange.setStatusCode(204);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
            return; // stop processing
        }

        if (!exchange.getRequestMethod().equalToString("POST")) {
            ResponseUtil.sendError(exchange, 405, "Method Not Allowed");
            return;
        }


        if (req.email == null || req.email.isBlank() ||
                req.password == null || req.password.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "Missing required fields: email and password are required");
            return;
        }

        String ipAddress = exchange.getSourceAddress() != null ? exchange.getSourceAddress().getHostString() : "unknown";
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String deviceName = (req.deviceName == null || req.deviceName.isBlank()) ? "unknown" : req.deviceName;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // 1. Fetch user
            String selectUser = """
                SELECT user_id, uuid, password_hash, first_name, last_name, user_email, is_deleted, role_id
                FROM users
                WHERE user_email = ?
            """;

            Long userId = null;
            UUID userUuid = null;
            String passwordHash = null;
            String email = null;
            String firstName = null;
            String lastName = null;
            Integer roleId = null;

            try (PreparedStatement ps = conn.prepareStatement(selectUser)) {
                ps.setString(1, req.email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 401, "Invalid credentials");
                        return;
                    }
                    userId = rs.getLong("user_id");
                    Object uuidObj = rs.getObject("uuid");
                    if (uuidObj == null) {
                        logger.error("User id={} has NULL uuid", userId);
                        ResponseUtil.sendError(exchange, 500, "Account error — missing UUID");
                        return;
                    }
                    userUuid = UUID.fromString(uuidObj.toString());
                    passwordHash = rs.getString("password_hash");
                    email = rs.getString("user_email");
                    firstName = rs.getString("first_name");
                    lastName = rs.getString("last_name");
                    roleId = rs.getObject("role_id") == null ? null : rs.getInt("role_id");

                    if (rs.getBoolean("is_deleted")) {
                        ResponseUtil.sendError(exchange, 403, "User is deleted. Contact administrator.");
                        return;
                    }
                }
            }

            //  Verify password
            if (!PasswordUtil.verifyPassword(req.password, passwordHash)) {
                ResponseUtil.sendError(exchange, 401, "Invalid credentials");
                return;
            }

            // Collect role name & permissions
            String roleName = null;
            Map<String, List<String>> formattedPermissions = new LinkedHashMap<>();
            if (roleId != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT role_name FROM roles WHERE role_id = ?")) {
                    ps.setInt(1, roleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) roleName = rs.getString("role_name");
                    }
                }

                String permSql = """
                    SELECT p.permission_code, rp.can_view, rp.can_create, rp.can_update, rp.can_delete
                    FROM role_permissions rp
                    JOIN permissions p ON rp.permission_id = p.permission_id
                    WHERE rp.role_id = ?
                """;
                try (PreparedStatement ps = conn.prepareStatement(permSql)) {
                    ps.setInt(1, roleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String code = rs.getString("permission_code");
                            List<String> actions = new ArrayList<>();
                            if (rs.getBoolean("can_view")) actions.add("view");
                            if (rs.getBoolean("can_create")) actions.add("create");
                            if (rs.getBoolean("can_update")) actions.add("update");
                            if (rs.getBoolean("can_delete")) actions.add("delete");
                            formattedPermissions.put(code, actions);
                        }
                    }
                }
            }

            // Generate refresh token and insert into auth_sessions
            String refreshToken = TokenUtil.generateToken();
            String refreshHash = TokenUtil.hashToken(refreshToken);
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(REFRESH_TOKEN_TTL);

            String insertAuth = """
                INSERT INTO auth_sessions
                (user_id, refresh_token_hash, jwt_id, issued_at, expires_at, ip_address, user_agent, device_name)
                VALUES (?, ?, uuid_generate_v4(), ?, ?, ?, ?, ?)
                RETURNING jwt_id
            """;

            UUID jwtId;
            try (PreparedStatement ps = conn.prepareStatement(insertAuth)) {
                ps.setLong(1, userId);
                ps.setString(2, refreshHash);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setTimestamp(4, Timestamp.from(expiresAt));
                ps.setString(5, ipAddress);
                ps.setString(6, userAgent);
                ps.setString(7, deviceName);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Failed to create auth_session");
                    jwtId = (UUID) rs.getObject("jwt_id");
                }
            }

            // Generate JWT access token
            String accessToken = JwtUtil.generateAccessTokenWithJti(
                    userUuid.toString(),
                    email,
                    roleName,
                    ACCESS_TOKEN_TTL,
                    jwtId
            );

            // Update last_login_at
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET last_login_at = ? WHERE user_id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, userId);
                ps.executeUpdate();
            }

            // Set refresh token as Secure HttpOnly cookie
            CookieImpl cookie = new CookieImpl("refreshToken", refreshToken);
            cookie.setHttpOnly(true);
            cookie.setSecure(true); // only send over HTTPS
            cookie.setPath("/");     // cookie valid site-wide
            cookie.setMaxAge((int) REFRESH_TOKEN_TTL);
            exchange.setResponseCookie(cookie);

            // Build response without refresh token
            Map<String, Object> userData = new HashMap<>();
            userData.put("uuid", userUuid);
            userData.put("fullName", firstName + " " + lastName);
            userData.put("email", email);
            userData.put("role", roleName);
            userData.put("permissions", formattedPermissions);

            Map<String, Object> data = new HashMap<>();
            data.put("accessToken", accessToken);
            data.put("expiresIn", ACCESS_TOKEN_TTL);
            data.put("user", userData);

            ResponseUtil.sendSuccess(exchange, "Login successful", data);

        } catch (SQLException sqle) {
            logger.error("SQL error during login", sqle);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        } catch (Exception e) {
            logger.error("Error during login", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
