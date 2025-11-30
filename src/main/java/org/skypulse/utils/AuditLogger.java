package org.skypulse.utils;

import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.dtos.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Log a user action to the audit_log table
 * - entity     Table or entity affected
 * - entityId   ID of the affected entity
 * - action     Action performed: CREATE / UPDATE / DELETE / ROLLBACK
 * - beforeData state before change (nullable)
 * - afterData  state after change (nullable)
 */
public class AuditLogger {
    private static final Logger logger = LoggerFactory.getLogger(AuditLogger.class);

    private AuditLogger() {}

    public static void log(HttpServerExchange exchange, String entity, Long entityId, String action,
                           Object beforeDataObj, Object afterDataObj) {
        if (exchange == null) {
            logger.warn("Audit log skipped: HttpServerExchange is null");
            return;
        }

        UserContext userContext = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (userContext == null || userContext.userId() == null) {
            logger.warn("Audit log skipped: missing userId in UserContext");
            return;
        }

        String ipAddress = exchange.getSourceAddress() != null
                ? exchange.getSourceAddress().getHostString()
                : "unknown";

        try (Connection conn = JdbcUtils.getConnection()) {
            String sql = """
                    INSERT INTO audit_log (user_id, entity, entity_id, action, before_data, after_data, ip_address, date_created, date_modified)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, NOW(), NOW());
                    """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userContext.userId());
                ps.setString(2, entity);
                if (entityId != null) {
                    ps.setLong(3, entityId);
                } else {
                    ps.setNull(3, java.sql.Types.BIGINT);
                }
                ps.setString(4, action);
                ps.setString(5, beforeDataObj != null
                        ? JsonUtil.mapper().writeValueAsString(beforeDataObj)
                        : "{}");
                ps.setString(6, afterDataObj != null
                        ? JsonUtil.mapper().writeValueAsString(afterDataObj)
                        : "{}");
                ps.setString(7, ipAddress);

                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Failed to insert audit log: {}", e.getMessage(), e);
        }
    }
}
