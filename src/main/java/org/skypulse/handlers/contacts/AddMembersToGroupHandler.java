package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AddMembersToGroupHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(AddMembersToGroupHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            logger.warn("Request rejected: invalid or empty JSON body");
            return;
        }

        Long groupId = HttpRequestUtil.getLong(body, "contactGroupId");
        var members = (List<Integer>) body.get("membersIds");

        if (groupId == null || members == null || members.isEmpty()) {
            logger.warn("Missing fields for adding members: groupId={} members={}", groupId, members);
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing fields");
            return;
        }

        logger.info("Adding {} members to group {}", members.size(), groupId);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);

            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO contact_group_members (contact_group_id, user_id, is_primary)
                VALUES (?, ?, FALSE)
                ON CONFLICT (contact_group_id, user_id) DO NOTHING;
            """);

            for (Integer id : members) {
                ps.setLong(1, groupId);
                ps.setLong(2, id);
                ps.addBatch();
            }

            int[] results = ps.executeBatch();
            conn.commit();

            int inserted = (int) java.util.Arrays.stream(results).filter(r -> r >= 0).count();

            logger.info("Added {} new members to group {}", inserted, groupId);
            ResponseUtil.sendSuccess(exchange, "Members added", null);

        } catch (Exception e) {
            logger.error("Failed to add members to group {}: {}", groupId, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
