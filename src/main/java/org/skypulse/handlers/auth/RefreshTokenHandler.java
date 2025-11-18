package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;

import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.security.TokenUtil;

import io.jsonwebtoken.security.SignatureException;

import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RefreshTokenHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    private static final long ACCESS_TTL_SECONDS = 900;

    private static final long REFRESH_TTL_SECONDS = 7 * 24 * 3600;

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        Map<String, Object> body = mapper.readValue(exchange.getInputStream(), Map.class);

        String oldRefreshToken = (String) body.get("refreshToken");

        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "refreshToken is required");
            return;
        }

        // Hash the incoming refresh token
        String oldHash;
        try {
            oldHash = TokenUtil.hashToken(oldRefreshToken);
        } catch (Exception e) {
            ResponseUtil.sendError(exchange, 400, "Invalid refresh token");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // 1) FETCH SESSION DETAILS
            String sql = """
                SELECT s.auth_session_id, s.user_id, s.jwt_id,
                       s.expires_at, s.is_revoked, s.replaced_by,
                       u.uuid AS user_uuid, u.user_email, r.role_name
                FROM auth_sessions s
                JOIN users u ON s.user_id = u.user_id
                JOIN roles r ON u.role_id = r.role_id
                WHERE s.refresh_token_hash = ?
            """;

            Long userId = null;
            UUID oldJti = null;
            Timestamp expiresAt = null;
            boolean isRevoked = false;
            Object replacedBy = null;
            String userUUID = null;
            String email = null;
            String role = null;
            String sessionId = null;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, oldHash);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 401, "Invalid refresh token");
                        return;
                    }

                    sessionId = rs.getString("auth_session_id");
                    userId = rs.getLong("user_id");
                    oldJti = UUID.fromString(rs.getString("jwt_id"));
                    expiresAt = rs.getTimestamp("expires_at");
                    isRevoked = rs.getBoolean("is_revoked");
                    replacedBy = rs.getObject("replaced_by");

                    userUUID = rs.getString("user_uuid");
                    email = rs.getString("user_email");
                    role = rs.getString("role_name");
                }
            }

            // 2) VALIDATE SESSION
            if (expiresAt.toInstant().isBefore(Instant.now())) {
                ResponseUtil.sendError(exchange, 401, "Refresh token expired");
                return;
            }

            if (isRevoked) {
                ResponseUtil.sendError(exchange, 401, "Refresh token revoked");
                return;
            }

            if (replacedBy != null) {
                ResponseUtil.sendError(exchange, 401, "Refresh token already used (rotation enforced)");
                return;
            }

            // 3) ROTATE TOKEN
            String newRefreshToken = TokenUtil.generateToken();   // uses your util
            String newRefreshHash = TokenUtil.hashToken(newRefreshToken);

            UUID newJti = UUID.randomUUID();
            Instant now = Instant.now();
            Instant refreshExpiry = now.plusSeconds(REFRESH_TTL_SECONDS);

            // 3A) mark old session replaced
            String updateOldSql = """
                UPDATE auth_sessions
                SET replaced_by = ?, replaced_at = NOW(), is_revoked = TRUE
                WHERE auth_session_id = ?
            """;

            try (PreparedStatement ps = conn.prepareStatement(updateOldSql)) {
                ps.setObject(1, newJti);
                ps.setObject(2, UUID.fromString(sessionId));
                ps.executeUpdate();
            }

            // 3B) create new session
            String insertNewSql = """
                INSERT INTO auth_sessions (
                    auth_session_id, user_id, refresh_token_hash, jwt_id,
                    issued_at, expires_at, ip_address, user_agent
                ) VALUES (?, ?, ?, ?, NOW(), ?, ?, ?)
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertNewSql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setLong(2, userId);
                ps.setString(3, newRefreshHash);
                ps.setObject(4, newJti);
                ps.setTimestamp(5, Timestamp.from(refreshExpiry));
                ps.setString(6, exchange.getSourceAddress().getAddress().toString());
                ps.setString(7, exchange.getRequestHeaders().getFirst("User-Agent"));
                ps.executeUpdate();
            }

            // 4) ISSUE NEW ACCESS TOKEN
            String newAccessToken = JwtUtil.generateAccessTokenWithJti(
                    userUUID,
                    email,
                    role,
                    ACCESS_TTL_SECONDS,
                    newJti
            );

            // 5) AUDIT LOG (OPTIONAL BUT RECOMMENDED)
            String auditSql = """
                INSERT INTO login_attempts (user_id, status, reason, ip_address)
                VALUES (?, 'SUCCESS', 'REFRESH_ROTATION', ?)
            """;

            try (PreparedStatement ps = conn.prepareStatement(auditSql)) {
                ps.setLong(1, userId);
                ps.setString(2, exchange.getSourceAddress().getAddress().toString());
                ps.executeUpdate();
            }

            // 6) RETURN NEW TOKENS
            ResponseUtil.sendSuccess(exchange, "Token refreshed successfully", Map.of(
                    "accessToken", newAccessToken,
                    "refreshToken", newRefreshToken
            ));

        } catch (SignatureException e) {
            ResponseUtil.sendError(exchange, 401, "Invalid signature");
        } catch (Exception e) {
            logger.error("Refresh token error", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
