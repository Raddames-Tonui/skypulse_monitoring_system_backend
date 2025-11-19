package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CookieImpl;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RefreshTokenHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenHandler.class);
    private static final long ACCESS_TOKEN_TTL = 3600L; // 1 hour
    private static final long REFRESH_TOKEN_TTL = 30L * 24L * 3600L; // 30 days

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        //  Get refresh token from HttpOnly cookie
        CookieImpl refreshCookie = (CookieImpl) exchange.getRequestCookie("refreshToken");
        if (refreshCookie == null || refreshCookie.getValue().isBlank()) {
            ResponseUtil.sendError(exchange, 401, "Refresh token missing or invalid");
            return;
        }
        String refreshToken = refreshCookie.getValue();

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // Verify refresh token exists in auth_sessions
            String sessionQuery = """
                SELECT user_id, jwt_id, expires_at
                FROM auth_sessions
                WHERE refresh_token_hash = ?
            """;

            long userId;
            UUID jwtId;
            Instant expiresAt;

            try (PreparedStatement ps = conn.prepareStatement(sessionQuery)) {
                ps.setString(1, TokenUtil.hashToken(refreshToken));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 401, "Invalid refresh token");
                        return;
                    }
                    userId = rs.getLong("user_id");
                    jwtId = (UUID) rs.getObject("jwt_id");
                    expiresAt = rs.getTimestamp("expires_at").toInstant();
                }
            }

            if (Instant.now().isAfter(expiresAt)) {
                ResponseUtil.sendError(exchange, 401, "Refresh token expired");
                return;
            }

            //  Fetch user info + role name in one query
            String userQuery = """
                SELECT u.user_email, u.uuid, r.role_name
                FROM users u
                LEFT JOIN roles r ON u.role_id = r.role_id
                WHERE u.user_id = ?
            """;

            String email;
            UUID userUuid;
            String roleName;

            try (PreparedStatement ps = conn.prepareStatement(userQuery)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 404, "User not found");
                        return;
                    }
                    email = rs.getString("user_email");
                    userUuid = (UUID) rs.getObject("uuid");
                    roleName = rs.getString("role_name");
                }
            }

            // Generate new access token
            String newAccessToken = JwtUtil.generateAccessTokenWithJti(
                    userUuid.toString(),
                    email,
                    roleName,
                    ACCESS_TOKEN_TTL,
                    jwtId
            );

            // Rotate refresh token
            String newRefreshToken = TokenUtil.generateToken();
            Instant newExpiresAt = Instant.now().plusSeconds(REFRESH_TOKEN_TTL);

            String updateAuth = """
                UPDATE auth_sessions
                SET refresh_token_hash = ?, expires_at = ?, last_used_at = ?
                WHERE jwt_id = ?
            """;
            try (PreparedStatement ps = conn.prepareStatement(updateAuth)) {
                ps.setString(1, TokenUtil.hashToken(newRefreshToken));
                ps.setTimestamp(2, Timestamp.from(newExpiresAt));
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setObject(4, jwtId);
                ps.executeUpdate();
            }

            // Set new HttpOnly cookie
            CookieImpl cookie = new CookieImpl("refreshToken", newRefreshToken);
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath("/");
            cookie.setMaxAge((int) REFRESH_TOKEN_TTL);
            exchange.setResponseCookie(cookie);

            // Respond with new access token
            ResponseUtil.sendSuccess(exchange, "Token refreshed", Map.of(
                    "accessToken", newAccessToken,
                    "expiresIn", ACCESS_TOKEN_TTL
            ));

        } catch (SQLException e) {
            logger.error("SQL error during refresh", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        } catch (Exception e) {
            logger.error("Error during token refresh", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
