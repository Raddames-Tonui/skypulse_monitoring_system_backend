package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.CookieUtil;
import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RefreshTokenHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenHandler.class);

    private final long ACCESS_TOKEN_TTL;
    private final long REFRESH_TOKEN_TTL;

    public RefreshTokenHandler(XmlConfiguration cfg) {
        long access = 3600;
        long refresh = 30 * 24 * 3600;
        try { access = Long.parseLong(cfg.jwtConfig.accessToken) * 60; } catch(Exception ignored) {}
        try { refresh = Long.parseLong(cfg.jwtConfig.refreshToken) * 24 * 3600; } catch(Exception ignored) {}
        this.ACCESS_TOKEN_TTL = access;
        this.REFRESH_TOKEN_TTL = refresh;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        var refreshCookie = exchange.getRequestCookie("refreshToken");
        if (refreshCookie == null || refreshCookie.getValue().isBlank()) {
            ResponseUtil.sendError(exchange, 401, "Refresh token missing or invalid");
            return;
        }

        String refreshToken = refreshCookie.getValue();
        String refreshTokenHash = TokenUtil.hashToken(refreshToken);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            String sessionQuery = """
                SELECT auth_session_id, user_id, jwt_id, expires_at, is_revoked
                FROM auth_sessions
                WHERE refresh_token_hash = ? AND session_status='active'
            """;

            UUID jwtId;
            long userId;
            Instant expiresAt;
            String authSessionId;

            try (PreparedStatement ps = conn.prepareStatement(sessionQuery)) {
                ps.setString(1, refreshTokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 401, "Invalid refresh token");
                        return;
                    }
                    authSessionId = rs.getString("auth_session_id");
                    userId = rs.getLong("user_id");
                    jwtId = (UUID) rs.getObject("jwt_id");
                    expiresAt = rs.getTimestamp("expires_at").toInstant();
                    if (rs.getBoolean("is_revoked")) {
                        ResponseUtil.sendError(exchange, 401, "Refresh token revoked");
                        return;
                    }
                }
            }

            if (Instant.now().isAfter(expiresAt)) {
                ResponseUtil.sendError(exchange, 401, "Refresh token expired");
                return;
            }

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
                        ResponseUtil.sendError(exchange, 401, "User not found");
                        return;
                    }
                    email = rs.getString("user_email");
                    userUuid = (UUID) rs.getObject("uuid");
                    roleName = rs.getString("role_name");
                }
            }

            String newAccessToken = JwtUtil.generateAccessTokenWithJti(
                    userUuid.toString(), email, roleName, ACCESS_TOKEN_TTL, jwtId
            );
            String newRefreshToken = TokenUtil.generateToken();
            Instant newExpiresAt = Instant.now().plusSeconds(REFRESH_TOKEN_TTL);

            String updateAuth = """
                UPDATE auth_sessions
                SET refresh_token_hash = ?, expires_at = ?, last_used_at = NOW(), date_modified = NOW()
                WHERE auth_session_id = ?
            """;
            try (PreparedStatement ps = conn.prepareStatement(updateAuth)) {
                ps.setString(1, TokenUtil.hashToken(newRefreshToken));
                ps.setTimestamp(2, Timestamp.from(newExpiresAt));
                ps.setObject(3, UUID.fromString(authSessionId));
                ps.executeUpdate();
            }

            boolean isSecure = exchange.getRequestScheme().equalsIgnoreCase("https");

            CookieUtil.setAccessTokenCookie(exchange, jwtId, userUuid, email, roleName, ACCESS_TOKEN_TTL, isSecure);
            CookieUtil.setRefreshTokenCookie(exchange, newRefreshToken, REFRESH_TOKEN_TTL, isSecure);

            ResponseUtil.sendSuccess(exchange, "Token refreshed", Map.of(
                    "accessToken", newAccessToken,
                    "expiresIn", ACCESS_TOKEN_TTL
            ));

        } catch (Exception e) {
            logger.error("Error during token refresh", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
