package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CreateContactGroupHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreateContactGroupHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            logger.warn("Invalid JSON received when creating contact group");
            return;
        }

        // Authentication
        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            logger.warn("Unauthorized request to create contact group");
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "Unauthorized");
            return;
        }

        long adminId = ctx.getUserId();

        // Fields
        String groupName = HttpRequestUtil.getString(body, "contact_group_name");
        String groupDescription = HttpRequestUtil.getString(body, "contact_group_description");
        List<Integer> memberIds = (List<Integer>) body.get("members_ids");

        if (groupName == null || groupName.isBlank()) {
            logger.warn("Contact group creation failed: missing group name (adminId={})", adminId);
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Contact group name is required");
            return;
        }

        logger.info("Request to create contact group '{}' by admin {}", groupName, adminId);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);

            long groupId;

            // Insert group
            String insertGroupSql = """
                INSERT INTO contact_groups (contact_group_name, contact_group_description, created_by)
                VALUES (?, ?, ?)
                RETURNING contact_group_id;
            """;

            try (PreparedStatement ps = conn.prepareStatement(insertGroupSql)) {
                ps.setString(1, groupName);
                ps.setString(2, groupDescription);
                ps.setLong(3, adminId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        groupId = rs.getLong("contact_group_id");
                    } else {
                        logger.error("Failed to obtain ID for newly created contact group '{}'", groupName);
                        conn.rollback();
                        ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to create contact group");
                        return;
                    }
                }
            }

            logger.debug("Contact group created with ID {} by admin {}", groupId, adminId);

            // Insert members
            if (memberIds != null && !memberIds.isEmpty()) {
                logger.debug("Adding {} members to group {}", memberIds.size(), groupId);

                String insertMembersSql = """
                    INSERT INTO contact_group_members (contact_group_id, user_id, is_primary)
                    VALUES (?, ?, FALSE)
                    ON CONFLICT (contact_group_id, user_id) DO NOTHING;
                """;

                try (PreparedStatement ps = conn.prepareStatement(insertMembersSql)) {
                    for (Integer memberId : memberIds) {
                        ps.setLong(1, groupId);
                        ps.setLong(2, memberId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                logger.info("Added {} members to group {}", memberIds.size(), groupId);
            }

            conn.commit();

            logger.info("Contact group '{}' (ID={}) created successfully by admin {}", groupName, groupId, adminId);

            ResponseUtil.sendCreated(exchange,
                    "Contact group created successfully",
                    Map.of("groupName", groupName)
            );

        } catch (Exception e) {
            logger.error("Failed to create contact group '{}': {}", groupName, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Failed to create contact group: " + e.getMessage());
        }
    }
}
