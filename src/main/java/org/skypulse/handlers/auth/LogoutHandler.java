package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class LogoutHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(LogoutHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        try {
            Cookie refreshCookie = exchange.getRequestCookie("refreshToken");
            if (refreshCookie == null || refreshCookie.getValue() == null || refreshCookie.getValue().isBlank()) {
                clearRefreshCookie(exchange);
                clearAccessCookie(exchange);
                ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                return;
            }

            final String refreshToken = refreshCookie.getValue();
            final String refreshTokenHash = TokenUtil.hashToken(refreshToken);

            logger.info("Received refresh token hash: {}", refreshTokenHash);

            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                final String selectSql = """
                        SELECT auth_session_id, is_revoked, expires_at
                        FROM auth_sessions
                        WHERE refresh_token_hash = ?
                          AND session_status = 'active'
                    """;

                UUID sessionId = null;
                boolean isRevoked = false;

                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, refreshTokenHash);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sessionId = (UUID) rs.getObject("auth_session_id");
                            isRevoked = rs.getBoolean("is_revoked");

                            Timestamp expiresAt = rs.getTimestamp("expires_at");
                            if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
                                isRevoked = true;
                            }
                        }
                    }
                }

                if (sessionId == null) {
                    clearRefreshCookie(exchange);
                    clearAccessCookie(exchange);
                    ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                    return;
                }

                // Revoke session if not already revoked ---
                if (!isRevoked) {
                    final String updateSql = """
                            UPDATE auth_sessions
                            SET is_revoked = TRUE,
                                revoked_at = NOW(),
                                logout_time = NOW(),
                                session_status = 'revoked',
                                date_modified = NOW()
                            WHERE auth_session_id = ?
                        """;

                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setObject(1, sessionId);
                        ps.executeUpdate();
                    }
                }

                clearRefreshCookie(exchange);
                clearAccessCookie(exchange);
                ResponseUtil.sendSuccess(exchange, "Logged out successfully", null);
            }

        } catch (Exception e) {
            logger.error("Logout failed", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }

    private void clearRefreshCookie(HttpServerExchange exchange) {
        CookieImpl clear = new CookieImpl("refreshToken", "");
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setSecure(true);
        clear.setMaxAge(0);
        exchange.setResponseCookie(clear);
    }

    private void clearAccessCookie(HttpServerExchange exchange) {
        CookieImpl clear = new CookieImpl("accessToken", "");
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setSecure(true);
        clear.setMaxAge(0);
        exchange.setResponseCookie(clear);
    }
}
