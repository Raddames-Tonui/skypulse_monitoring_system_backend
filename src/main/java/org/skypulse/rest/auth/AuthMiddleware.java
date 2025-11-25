package org.skypulse.rest.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * AuthMiddleware handles authenticated requests:
 * - Validates access & refresh tokens
 * - Auto-refreshes access token if expired
 * - Loads user context from DB
 */
public class AuthMiddleware implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);

    private final HttpHandler next;
    private final long ACCESS_TOKEN_TTL;

    public AuthMiddleware(HttpHandler next, long accessTokenTtl) {
        this.next = next;
        this.ACCESS_TOKEN_TTL = accessTokenTtl;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        CookieImpl accessCookie = (CookieImpl) exchange.getRequestCookie("accessToken");
        CookieImpl refreshCookie = (CookieImpl) exchange.getRequestCookie("refreshToken");

        String accessToken = accessCookie != null ? accessCookie.getValue() : null;
        String refreshToken = refreshCookie != null ? refreshCookie.getValue() : null;

        if ((accessToken == null || accessToken.isBlank()) &&
                (refreshToken == null || refreshToken.isBlank())) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED,
                    "No active session. Please login.");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            UUID accessJti = null;
            if (accessToken != null && !accessToken.isBlank()) {
                String jti = JwtUtil.getJwtId(accessToken);
                if (jti != null) accessJti = UUID.fromString(jti);
            }

            String refreshHash = refreshToken != null ? TokenUtil.hashToken(refreshToken) : null;

            String sql = """
                SELECT auth_session_id, user_id, jwt_id, refresh_token_hash,
                       session_status, is_revoked, expires_at
                FROM auth_sessions
                WHERE (jwt_id IS NOT DISTINCT FROM ?)
                   OR (refresh_token_hash IS NOT DISTINCT FROM ?)
                LIMIT 1
            """;

            boolean sessionValid = false;
            boolean matchedViaRefresh = false;
            Long userId = null;
            UUID jwtId = null;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, accessJti, Types.OTHER);
                ps.setString(2, refreshHash);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean revoked = rs.getBoolean("is_revoked");
                        String status = rs.getString("session_status");
                        Timestamp expiresAt = rs.getTimestamp("expires_at");

                        if (!revoked && "active".equals(status) &&
                                expiresAt != null && expiresAt.toInstant().isAfter(Instant.now())) {

                            sessionValid = true;
                            userId = rs.getLong("user_id");
                            jwtId = (UUID) rs.getObject("jwt_id");
                            matchedViaRefresh = refreshHash != null &&
                                    refreshHash.equals(rs.getString("refresh_token_hash"));
                        }
                    }
                }
            }

            if (!sessionValid || jwtId == null) {
                ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED,
                        "Session expired or does not exist. Please login.");
                return;
            }

            String userSql = """
                    SELECT u.uuid, u.first_name, u.last_name, u.user_email,
                           u.role_id, r.role_name, c.company_name
                    FROM users u
                    LEFT JOIN roles r ON u.role_id = r.role_id
                    LEFT JOIN company c ON u.company_id = c.company_id
                    WHERE u.user_id = ?
            """;

            UUID userUuid = null;
            String firstName = null;
            String lastName = null;
            String email = null;
            Integer roleId = null;
            String roleName = "";
            String companyName = null;

            try (PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setLong(1, userId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userUuid = UUID.fromString(rs.getString("uuid"));
                        firstName = rs.getString("first_name");
                        lastName = rs.getString("last_name");
                        email = rs.getString("user_email");
                        roleId = rs.getInt("role_id");
                        roleName = rs.getString("role_name");
                        companyName = rs.getString("company_name");
                    }
                }
            }

            // Auto-refresh access token if expired
            boolean accessExpired = (accessToken == null) || JwtUtil.isExpired(accessToken);

            if (accessExpired && refreshToken != null && matchedViaRefresh) {
                if (userUuid != null) {
                    getUserByUuid(exchange, jwtId, userUuid, email, roleName, ACCESS_TOKEN_TTL);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE auth_sessions SET last_used_at = ? WHERE jwt_id = ?"
            )) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.setObject(2, jwtId, Types.OTHER);
                ps.executeUpdate();
            }

            UserContext ctx = new UserContext(
                    userId, userUuid, firstName, lastName,
                    email, roleId, roleName, companyName
            );

            exchange.putAttachment(UserContext.ATTACHMENT_KEY, ctx);

            next.handleRequest(exchange);

        } catch (Exception e) {
            log.error("AuthMiddleware failed: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Internal Server Error");
        }
    }

    // Generate new access token
    public static void getUserByUuid(HttpServerExchange exchange,
                                     UUID jwtId,
                                     UUID userUuid,
                                     String email,
                                     String roleName,
                                     long accessTokenTtl) {

        String newAccess = JwtUtil.generateAccessTokenWithJti(
                userUuid.toString(),
                email,
                roleName,
                accessTokenTtl,
                jwtId
        );

        CookieImpl newCookie = new CookieImpl("accessToken", newAccess);
        newCookie.setHttpOnly(true);
        newCookie.setSecure(true);
        newCookie.setPath("/");
        newCookie.setMaxAge((int) accessTokenTtl);
        exchange.setResponseCookie(newCookie);
    }
}
