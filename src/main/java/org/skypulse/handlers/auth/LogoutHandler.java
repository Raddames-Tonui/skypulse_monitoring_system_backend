package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

public class LogoutHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(LogoutHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

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

            try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

                String sqlFind = """
                    SELECT user_id, auth_session_id, is_revoked
                    FROM auth_sessions
                    WHERE refresh_token_hash = ?
                """;

                Long userId = null;
                String sessionId = null;
                boolean isRevoked = false;

                try (PreparedStatement ps = conn.prepareStatement(sqlFind)) {
                    ps.setString(1, refreshTokenHash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getLong("user_id");
                            sessionId = rs.getString("auth_session_id");
                            isRevoked = rs.getBoolean("is_revoked");
                        }
                    }
                }

                // If session doesn't exist → treat as logged out
                if (userId == null || sessionId == null) {
                    clearRefreshCookie(exchange);
                    ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                    return;
                }

                //  Revoke session if not already revoked
                if (!isRevoked) {
                    String sqlRevoke = """
                        UPDATE auth_sessions
                        SET is_revoked = TRUE, revoked_at = NOW(), date_modified = NOW()
                        WHERE refresh_token_hash = ?
                    """;
                    try (PreparedStatement ps = conn.prepareStatement(sqlRevoke)) {
                        ps.setString(1, refreshTokenHash);
                        ps.executeUpdate();
                    }
                }

                //  Update audit trail
                String sqlAudit = """
                    UPDATE user_audit_session
                    SET logout_time = NOW(),
                        session_status = 'revoked',
                        date_modified = NOW()
                    WHERE user_id = ?
                      AND session_token = ?
                      AND session_status = 'active'
                """;

                try (PreparedStatement ps = conn.prepareStatement(sqlAudit)) {
                    ps.setLong(1, userId);
                    ps.setString(2, sessionId);
                    ps.executeUpdate();
                }

                // Clear refresh cookie on client
                clearRefreshCookie(exchange);

                ResponseUtil.sendSuccess(exchange, "Logged out successfully", null);

            }

        } catch (Exception e) {
            logger.error("Logout failed", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * Clears the refreshToken cookie from the browser.
     */
    private void clearRefreshCookie(HttpServerExchange exchange) {
        CookieImpl clear = new CookieImpl("refreshToken", "");
        clear.setPath("/");
        clear.setHttpOnly(true);
        clear.setSecure(true);
        clear.setMaxAge(0);
        exchange.setResponseCookie(clear);
    }
}
