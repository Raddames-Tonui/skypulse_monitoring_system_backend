package org.skypulse.config.security;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;

/**
 * AuthMiddleware validates JWT tokens and ensures the session is valid (not revoked/expired).
 * It expects:
 *   - sub => user's uuid
 *   - jti => jwt_id (UUID) that matches auth_sessions.jwt_id
 */
public class AuthMiddleware implements HttpHandler {

    private final HttpHandler next;

    public AuthMiddleware(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ResponseUtil.sendError(exchange, 401, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        String userUuidStr = JwtUtil.getUserId(token);
        String jtiStr = JwtUtil.getJwtId(token);

        if (userUuidStr == null || jtiStr == null) {
            ResponseUtil.sendError(exchange, 401, "Invalid token");
            return;
        }

        // basic UUID validation
        UUID userUuid, jtiUuid;
        try {
            userUuid = UUID.fromString(userUuidStr);
            jtiUuid = UUID.fromString(jtiStr);
        } catch (IllegalArgumentException ex) {
            ResponseUtil.sendError(exchange, 401, "Invalid token identifiers");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            // load user by uuid
            String userSql = """
                SELECT user_id, uuid, first_name, last_name, user_email, role_id, is_deleted, is_active
                FROM users
                WHERE uuid = ?
            """;

            Long userId = null;
            java.util.UUID dbUuid = null;
            String firstName = null;
            String lastName = null;
            String email = null;
            Integer roleId = null;
            boolean isDeleted = false;
            boolean isActive = false;

            try (PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setObject(1, userUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 401, "User not found");
                        return;
                    }
                    userId = rs.getLong("user_id");
                    dbUuid = UUID.fromString(rs.getString("uuid"));
                    firstName = rs.getString("first_name");
                    lastName = rs.getString("last_name");
                    email = rs.getString("user_email");
                    roleId = rs.getObject("role_id") == null ? null : rs.getInt("role_id");
                    isDeleted = rs.getBoolean("is_deleted");
                    isActive = rs.getBoolean("is_active");
                }
            }

            if (isDeleted) {
                ResponseUtil.sendError(exchange, 403, "User account has been deleted");
                return;
            }
            if (!isActive) {
                ResponseUtil.sendError(exchange, 403, "User account is inactive");
                return;
            }

            // check auth_sessions: matching user_id && jwt_id (jti) and not revoked and not expired
            String sessionSql = """
                SELECT is_revoked, expires_at
                FROM auth_sessions
                WHERE user_id = ? AND jwt_id = ?
                LIMIT 1
            """;

            boolean validSession = false;
            try (PreparedStatement ps = conn.prepareStatement(sessionSql)) {
                ps.setLong(1, userId);
                ps.setObject(2, jtiUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean isRevoked = rs.getBoolean("is_revoked");
                        java.sql.Timestamp expiresAt = rs.getTimestamp("expires_at");
                        if (!isRevoked && expiresAt != null && expiresAt.after(new java.util.Date())) {
                            validSession = true;
                        }
                    }
                }
            }

            if (!validSession) {
                ResponseUtil.sendError(exchange, 401, "Token is revoked or expired");
                return;
            }

            // Attach user context (use your existing UserContext class)
            UserContext ctx = new UserContext(userId, dbUuid, firstName, lastName, email, roleId);
            exchange.putAttachment(UserContext.ATTACHMENT_KEY, ctx);

            // proceed
            next.handleRequest(exchange);

        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
