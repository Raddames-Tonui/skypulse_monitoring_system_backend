package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class AddMembersToGroupHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(AddMembersToGroupHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        if (!"ADMIN".equals(ctx.getRoleName())) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "Insufficient permissions");
            return;
        }

        String groupUuidStr = exchange.getQueryParameters().get("id") != null ?
                exchange.getQueryParameters().get("id").getFirst() : null;
        if (groupUuidStr == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing group ID in URL");
            return;
        }

        UUID groupUuid;
        try {
            groupUuid = UUID.fromString(groupUuidStr);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid group UUID");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) return;

        List<Integer> members = (List<Integer>) body.get("membersIds");
        if (members == null || members.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing or empty membersIds array");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);

            String groupSql = "SELECT contact_group_id FROM contact_groups WHERE uuid = ? AND is_deleted = FALSE";
            Long groupId;
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setObject(1, groupUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Contact group not found");
                        return;
                    }
                    groupId = rs.getLong("contact_group_id");
                }
            }

            logger.info("Adding {} members to group {} (UUID={}) by user {}", members.size(), groupId, groupUuid, ctx.getEmail());

            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO contact_group_members (contact_group_id, user_id, is_primary)
                VALUES (?, ?, FALSE)
                ON CONFLICT (contact_group_id, user_id) DO NOTHING;
            """);

            for (Integer memberId : members) {
                ps.setLong(1, groupId);
                ps.setLong(2, memberId);
                ps.addBatch();
            }

            int[] results = ps.executeBatch();
            conn.commit();

            int inserted = (int) java.util.Arrays.stream(results).filter(r -> r >= 0).count();

            logger.info("Added {} new members to group {} (UUID={})", inserted, groupId, groupUuid);
            ResponseUtil.sendCreated(exchange, "Members added successfully", null);

        } catch (Exception e) {
            logger.error("Failed to add members to group  (UUID={}): {}", groupUuid, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
