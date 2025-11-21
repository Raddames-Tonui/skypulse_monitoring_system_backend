package org.skypulse.utils;

import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Log a user action to the audit_log table
 * -  exchange   HttpServerExchange to extract JWT token and IP
 * -  entity     Table or entity affected
 * -  entityId   ID of the affected entity
 * -  action - Action performed: CREATE / UPDATE / DELETE
 * -  beforeData  state before change (nullable)
 * -  afterData   state after change (nullable)
 */
public class AuditLogger {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogger.class);

    private AuditLogger() {}

    public static void log(HttpServerExchange exchange, String entity, Long entityId, String action, String beforeData, String afterData) throws SQLException {
        if (exchange == null) {
            logger.warn("Audit log skipped: HttpServerExchange is null");
            return;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("Audit log skipped: HttpServerExchange is null");
            return;
        }

        String token = authHeader.substring("Bearer ".length());

        long userId;
        try {
            String userUuid = JwtUtil.getUserUUId(token);
            if (userUuid == null) {
                logger.warn("Audit log skipped: cannot extract user UUID from JWT token");
                return;
            }
            userId = JwtUtil.getUserIdFromUuid(userUuid);
        } catch (Exception e) {
            logger.warn("Audit log failed: {}", e.getMessage(), e);
            return;
        }

        String ipAddress = exchange.getSourceAddress() != null
                ? exchange.getSourceAddress().getHostString()
                : "unknown";

        try (Connection conn = JdbcUtils.getConnection()) {
             String sql = """
                        INSERT INTO audit_log (user_id, entity, entity_id, action, before_data, after_data, ip_address,date_created,date_modified )
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, NOW(), NOW());
                        """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setString(2, entity);
                if (entityId != null ){
                    ps.setLong(3, entityId);
                } else {
                    ps.setNull(3, java.sql.Types.BIGINT);
                }
                ps.setString(4, action);
                ps.setString(5, beforeData);
                ps.setString(6, afterData);
                ps.setString(7, ipAddress);

                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to insert audit log: {}", e.getMessage(), e);
        }

    }
}
