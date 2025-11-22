package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.security.TokenUtil;
import org.skypulse.utils.ResponseUtil;
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
            Cookie cookie = exchange.getRequestCookie("refreshToken");
            if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
                clearRefreshCookie(exchange);
                ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                return;
            }

            String refreshToken = cookie.getValue();
            String refreshTokenHash = TokenUtil.hashToken(refreshToken);

            logger.info("Refresh token hash: {}", refreshTokenHash);

            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

                String selectSql = """
                    SELECT auth_session_id, is_revoked, expires_at
                    FROM auth_sessions
                    WHERE refresh_token_hash = ? AND session_status = 'active'
                """;

                UUID authSessionId = null;
                boolean alreadyRevoked = false;

                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, refreshTokenHash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            authSessionId = (UUID) rs.getObject("auth_session_id");
                            alreadyRevoked = rs.getBoolean("is_revoked");
                            Timestamp expiresAt = rs.getTimestamp("expires_at");
                            if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
                                alreadyRevoked = true;
                            }
                        }
                    }
                }

                if (authSessionId == null) {
                    clearRefreshCookie(exchange);
                    ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                    return;
                }

                if (!alreadyRevoked) {
                    String updateSql = """
                        UPDATE auth_sessions
                        SET is_revoked = TRUE,
                            revoked_at = NOW(),
                            logout_time = NOW(),
                            session_status = 'revoked',
                            date_modified = NOW()
                        WHERE auth_session_id = ?
                    """;
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setObject(1, authSessionId);
                        ps.executeUpdate();
                    }
                }

                clearRefreshCookie(exchange);
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
}
