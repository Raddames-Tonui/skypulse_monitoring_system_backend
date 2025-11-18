package org.skypulse.handlers.notifications;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.handlers.contacts.dto.ContactGroupRequest;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Objects;

public class CreateContactGroupHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        ContactGroupRequest req = JsonUtil.mapper().readValue(exchange.getInputStream(), ContactGroupRequest.class);

        if (req.contactGroupName == null || req.contactGroupName.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "contactGroupName is required");
            return;
        }

        long adminId = (long) exchange.getAttachment(org.skypulse.utils.security.AuthMiddleware.USER_ID_KEY); // From JWT

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // 1️⃣ Insert the group
            String insertGroup = """
                INSERT INTO contact_groups(contact_group_name, contact_group_description, created_by)
                VALUES (?, ?, ?)
                RETURNING contact_group_id
            """;

            long groupId;
            try (PreparedStatement ps = conn.prepareStatement(insertGroup)) {
                ps.setString(1, req.contactGroupName);
                ps.setString(2, req.contactGroupDescription);
                ps.setLong(3, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    groupId = rs.getLong("contact_group_id");
                }
            }

            // 2️⃣ Add members
            if (req.memberIds != null) {
                String insertMember = """
                    INSERT INTO contact_group_members(contact_group_id, user_id, is_primary)
                    VALUES (?, ?, FALSE)
                    ON CONFLICT(contact_group_id, user_id) DO NOTHING
                """;
                try (PreparedStatement ps = conn.prepareStatement(insertMember)) {
                    for (Long userId : req.memberIds) {
                        ps.setLong(1, groupId);
                        ps.setLong(2, userId);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            ResponseUtil.sendSuccess(exchange, "Contact group created successfully", Map.of("groupId", groupId));
        }
    }
}
