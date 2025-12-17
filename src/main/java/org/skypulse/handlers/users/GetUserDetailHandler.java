package org.skypulse.handlers.users;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.ResponseUtil;

import java.util.*;

public class GetUserDetailHandler implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {


        Map<String, Deque<String>> params = exchange.getQueryParameters();
        String uuidParam = DatabaseUtils.getParam(params, "uuid");
        if (uuidParam == null || uuidParam.isBlank()) {
            ResponseUtil.sendError(exchange, 400, "Missing required parameter: uuid");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidParam);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, 400, "Invalid UUID format");
            return;
        }

        String sql = """
            SELECT
                u.user_id, u.uuid, u.first_name, u.last_name, u.user_email, u.is_active,
                u.date_created, u.date_modified, u.is_deleted, u.deleted_at,
                r.role_name AS role,
                c.company_name AS company,
                uc.user_contacts_id, uc.type AS contact_type, uc.value AS contact_value,
                uc.verified AS contact_verified, uc.is_primary AS contact_is_primary,
                cg.contact_group_id, cg.uuid AS group_uuid, cg.contact_group_name, cg.contact_group_description
            FROM users u
            LEFT JOIN roles r ON u.role_id = r.role_id
            LEFT JOIN company c ON u.company_id = c.company_id
            LEFT JOIN user_contacts uc ON u.user_id = uc.user_id
            LEFT JOIN contact_group_members cgm ON u.user_id = cgm.user_id
            LEFT JOIN contact_groups cg ON cgm.contact_group_id = cg.contact_group_id
            WHERE u.uuid = ?::uuid
        """;

        List<Map<String, Object>> rows = DatabaseUtils.query(sql, Collections.singletonList(uuid));
        if (rows.isEmpty()) {
            ResponseUtil.sendError(exchange, 404, "User not found");
            return;
        }

        Map<String, Object> user = new HashMap<>();
        List<Map<String, Object>> contacts = new ArrayList<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        Set<Long> seenContacts = new HashSet<>();
        Set<Long> seenGroups = new HashSet<>();

        for (Map<String, Object> row : rows) {
            if (user.isEmpty()) {
                user.put("uuid", row.get("uuid"));
                user.put("first_name", row.get("first_name"));
                user.put("last_name", row.get("last_name"));
                user.put("user_email", row.get("user_email"));
                user.put("is_active", row.get("is_active"));
                user.put("date_created", row.get("date_created"));
                user.put("date_modified", row.get("date_modified"));
                user.put("is_deleted", row.get("is_deleted"));
                user.put("deleted_at", row.get("deleted_at"));
                user.put("role", row.get("role"));
                user.put("company", row.get("company"));
            }

            // Contacts
            Object contactIdObj = row.get("user_contacts_id");
            if (contactIdObj != null) {
                long contactId = ((Number) contactIdObj).longValue();
                if (seenContacts.add(contactId)) {
                    Map<String, Object> contact = new HashMap<>();
                    contact.put("id", contactId);
                    contact.put("type", row.get("contact_type"));
                    contact.put("value", row.get("contact_value"));
                    contact.put("verified", row.get("contact_verified"));
                    contact.put("is_primary", row.get("contact_is_primary"));
                    contacts.add(contact);
                }
            }

            // Groups
            Object groupIdObj = row.get("contact_group_id");
            if (groupIdObj != null) {
                long groupId = ((Number) groupIdObj).longValue();
                if (seenGroups.add(groupId)) {
                    Map<String, Object> group = new HashMap<>();
                    group.put("id", groupId);
                    group.put("uuid", row.get("group_uuid"));
                    group.put("name", row.get("contact_group_name"));
                    group.put("description", row.get("contact_group_description"));
                    groups.add(group);
                }
            }
        }

        user.put("contacts", contacts);
        user.put("groups", groups);

        ResponseUtil.sendSuccess(exchange, "User fetched successfully", user);
    }
}
