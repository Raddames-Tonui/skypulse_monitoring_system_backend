package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
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
    private static final long REFRESH_TOKEN_TTL = 30L * 24L * 3600L;
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserLoginRequest req = mapper.readValue(exchange.getInputStream(), UserLoginRequest.class);

        if (req.userEmail == null || req.userEmail.isBlank() ||
                req.password == null || req.password.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "Missing required fields: userEmail and password are required");
            return;
        }

        String ipAddress = exchange.getSourceAddress() != null
                ? exchange.getSourceAddress().getHostString()
                : "unknown";

        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String deviceName = (req.deviceName == null || req.deviceName.isBlank()) ? "unknown" : req.deviceName;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // 1. Fetch user
            String selectUser = """
                SELECT u.user_id, u.uuid, u.password_hash, u.first_name, u.last_name,
                       u.user_email, u.is_deleted, r.role_name, u.role_id
                FROM users u
                LEFT JOIN roles r ON u.role_id = r.role_id
                WHERE u.user_email = ?
            """;

            Long userId = null;
            UUID userUUID = null;
            String passwordHash = null;
            String userEmail = null;
            String firstName = null;
            String lastName = null;
            String roleName = null;
            Integer roleId = null;
            boolean isDeleted = false;

            try (PreparedStatement ps = conn.prepareStatement(selectUser)) {
                ps.setString(1, req.userEmail);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getLong("user_id");

                        Object uuidObj = rs.getObject("uuid");
                        if (uuidObj == null) {
                            logger.error("User id={} has NULL uuid in DB!", userId);
                            ResponseUtil.sendError(exchange, 500, "Account error — missing UUID");
                            return;
                        }

                        userUUID = UUID.fromString(uuidObj.toString());
                        passwordHash = rs.getString("password_hash");
                        userEmail = rs.getString("user_email");
                        firstName = rs.getString("first_name");
                        lastName = rs.getString("last_name");
                        roleName = rs.getString("role_name");

                        int roleValue = rs.getInt("role_id");
                        roleId = rs.wasNull() ? null : roleValue;
                        isDeleted = rs.getBoolean("is_deleted");

                        if (isDeleted) {
                            ResponseUtil.sendError(exchange, 403, "User is deleted. Contact administrator.");
                            return;
                        }

                    } else {
                        ResponseUtil.sendError(exchange, 401, "Invalid credentials");
                        return;
                    }
                }
            }

            // 2. Validate password
            if (!PasswordUtil.verifyPassword(req.password, passwordHash)) {
                ResponseUtil.sendError(exchange, 401, "Invalid credentials");
                return;
            }

            // 3. Fetch permissions
            Map<String, List<String>> formattedPermissions = new LinkedHashMap<>();

            if (roleId != null && roleId > 0) {
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

            // 4. Create refresh token & auth_session record
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
                    if (!rs.next()) {
                        throw new SQLException("Failed to retrieve generated JWT ID from auth_sessions");
                    }
                    jwtId = (UUID) rs.getObject("jwt_id");
                }
            }

            // 5. Generate access token (ALWAYS use userUUID)
            String accessToken = JwtUtil.generateAccessTokenWithJti(
                    userUUID.toString(),     // ✔ ALWAYS UUID
                    userEmail,
                    roleName,
                    ACCESS_TOKEN_TTL,
                    jwtId
            );

            // 6. Update last login
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET last_login_at = ? WHERE user_id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, userId);
                ps.executeUpdate();
            }

            // 7. Build response
            Map<String, Object> userData = new HashMap<>();
            userData.put("uuid", userUUID);
            userData.put("firstName", firstName);
            userData.put("lastName", lastName);
            userData.put("email", userEmail);
            userData.put("role", roleName);
            userData.put("permissions", formattedPermissions);

            Map<String, Object> data = new HashMap<>();
            data.put("accessToken", accessToken);
            data.put("refreshToken", refreshToken);
            data.put("expiresIn", ACCESS_TOKEN_TTL);
            data.put("user", userData);

            ResponseUtil.sendSuccess(exchange, "Login successful", data);

        } catch (SQLException sqle) {
            logger.error("SQL error: ", sqle);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
