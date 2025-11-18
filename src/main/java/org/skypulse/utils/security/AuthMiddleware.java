package org.skypulse.utils.security;

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
 * AuthMiddleware validates JWT tokens, checks user status, and enforces session revocation.
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
        String userUuid;
        try {
            userUuid = JwtUtil.getUserId(token);
            if (userUuid == null) {
                ResponseUtil.sendError(exchange, 401, "Invalid token");
                return;
            }
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 401, "Invalid token");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            // Fetch user by UUID
            String userSql = "SELECT user_id, uuid, first_name, last_name, user_email, role_id, is_deleted, status " +
                    "FROM users WHERE uuid = ?";
            Long userId = null;
            UUID uuid = null;
            String firstName = null;
            String lastName = null;
            String email = null;
            Integer roleId = null;
            boolean isDeleted = false;
            boolean isActive = false;

            try (PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setObject(1, UUID.fromString(userUuid));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getLong("user_id");
                        uuid = UUID.fromString(rs.getString("uuid"));
                        firstName = rs.getString("first_name");
                        lastName = rs.getString("last_name");
                        email = rs.getString("user_email");
                        roleId = rs.getInt("role_id");
                        isDeleted = rs.getBoolean("is_deleted");
                        isActive = rs.getBoolean("status");
                    } else {
                        ResponseUtil.sendError(exchange, 401, "User not found");
                        return;
                    }
                }
            }

            if (isDeleted) {
                ResponseUtil.sendError(exchange, 403, "User account has been deleted");
                return;
            }
//            if (!isActive) {
//                ResponseUtil.sendError(exchange, 403, "User account is locked or inactive");
//                return;
//            }

            // --- 3 Check token revocation in auth_sessions ---
            String sessionSql = "SELECT is_revoked, expires_at " +
                    "FROM auth_sessions WHERE user_id = ? AND jwt_id = ?";

            boolean validSession = false;
            try (PreparedStatement ps = conn.prepareStatement(sessionSql)) {
                ps.setLong(1, userId);
                ps.setObject(2, UUID.fromString(Objects.requireNonNull(JwtUtil.getJwtId(token))));
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

            // Attach user context
            UserContext ctx = new UserContext(userId, uuid, firstName, lastName, email, roleId);
            exchange.putAttachment(UserContext.ATTACHMENT_KEY, ctx);

            next.handleRequest(exchange);

        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
