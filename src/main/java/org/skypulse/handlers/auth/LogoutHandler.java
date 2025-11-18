package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Objects;

/**
 * Logout by refresh token. Preferred behavior: mark the auth_sessions row revoked (do not delete),
 * and update the audit session row.
 */
public class LogoutHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(LogoutHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        Map<String, Object> body = mapper.readValue(exchange.getInputStream(), Map.class);
        String refreshToken = (String) body.get("refreshToken");

        if (refreshToken == null || refreshToken.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "refreshToken is required");
            return;
        }

        String refreshTokenHash = TokenUtil.hashToken(refreshToken);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            String findSql = "SELECT user_id, auth_session_id, is_revoked FROM auth_sessions WHERE refresh_token_hash = ?";
            Long userId = null;
            String sessionId = null;
            boolean alreadyRevoked = false;

            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, refreshTokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getLong("user_id");
                        sessionId = rs.getString("auth_session_id");
                        alreadyRevoked = rs.getBoolean("is_revoked");
                    }
                }
            }

            if (userId == null || sessionId == null) {
                ResponseUtil.sendSuccess(exchange, "Already logged out", null);
                return;
            }

            if (!alreadyRevoked) {
                String revokeSql = "UPDATE auth_sessions SET is_revoked = TRUE, revoked_at = NOW(), date_modified = NOW() WHERE refresh_token_hash = ?";

                try (PreparedStatement ps = conn.prepareStatement(revokeSql)) {
                    ps.setString(1, refreshTokenHash);
                    ps.executeUpdate();
                }
            }

            String auditSql = """
                UPDATE user_audit_session
                SET logout_time = NOW(),
                    session_status = 'revoked',
                    date_modified = NOW()
                WHERE user_id = ? AND session_token = ? AND session_status = 'active'
            """;

            try (PreparedStatement ps = conn.prepareStatement(auditSql)) {
                ps.setLong(1, userId);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }

            ResponseUtil.sendSuccess(exchange, "Logged out successfully", null);

        } catch (Exception e) {
            logger.error("Logout failed", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
