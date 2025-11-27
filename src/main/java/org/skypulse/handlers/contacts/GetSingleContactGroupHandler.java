package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.Deque;
import java.util.UUID;

@RequireRoles({"ADMIN"})
public class GetSingleContactGroupHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GetSingleContactGroupHandler.class);

    private static final String QUERY = """
        SELECT
            cg.contact_group_id, cg.uuid AS group_uuid, cg.contact_group_name, cg.contact_group_description,
        
            u.user_id, u.uuid AS user_uuid, u.first_name, u.last_name,
            uc.type AS contact_type, uc.value AS contact_value,

            ms.monitored_service_id, ms.uuid AS service_uuid, ms.monitored_service_name,
            nc.notification_channel_id, nc.notification_channel_code, nc.notification_channel_name

        FROM contact_groups cg
        LEFT JOIN contact_group_members cgm ON cg.contact_group_id = cgm.contact_group_id
        LEFT JOIN users u ON cgm.user_id = u.user_id
        LEFT JOIN user_contacts uc ON u.user_id = uc.user_id
        LEFT JOIN monitored_services_contact_groups mscg ON cg.contact_group_id = mscg.contact_group_id
        LEFT JOIN monitored_services ms ON mscg.monitored_service_id = ms.monitored_service_id
        LEFT JOIN notification_channels nc ON nc.is_enabled = TRUE
        WHERE cg.is_deleted = FALSE AND cg.uuid = ?
        ORDER BY cg.contact_group_id, u.user_id
        """;

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Deque<String> uuidParam = exchange.getQueryParameters().get("uuid");
        if (uuidParam == null || uuidParam.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing uuid parameter");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidParam.getFirst());
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
             PreparedStatement ps = conn.prepareStatement(QUERY)) {

            ps.setObject(1, uuid);
            ResultSet rs = ps.executeQuery();

            Map<String, Object> group = null;
            Map<Long, Map<String, Object>> membersMap = new LinkedHashMap<>();
            Map<Long, Map<String, Object>> servicesMap = new LinkedHashMap<>();
            Map<Long, Map<String, Object>> channelsMap = new LinkedHashMap<>();

            while (rs.next()) {

                if (group == null) {
                    group = new LinkedHashMap<>();
                    group.put("contact_group_id", rs.getLong("contact_group_id"));
                    group.put("uuid", rs.getString("group_uuid"));
                    group.put("contact_group_name", rs.getString("contact_group_name"));
                    group.put("contact_group_description", rs.getString("contact_group_description"));
                    group.put("members", new ArrayList<Map<String, Object>>());
                    group.put("monitored_services", new ArrayList<Map<String, Object>>());
                    group.put("notification_channels", new ArrayList<Map<String, Object>>());
                }

                // --------- Members ---------
                long userId = rs.getLong("user_id");
                if (!rs.wasNull()) {
                    Map<String, Object> member;
                    if (membersMap.containsKey(userId)) {
                        member = membersMap.get(userId);
                    } else {
                        member = new LinkedHashMap<>();
                        member.put("user_id", userId);
                        member.put("uuid", rs.getString("user_uuid"));
                        member.put("first_name", rs.getString("first_name"));
                        member.put("last_name", rs.getString("last_name"));
                        member.put("contacts", new ArrayList<Map<String, String>>());
                        membersMap.put(userId, member);
                        ((List<Map<String, Object>>) group.get("members")).add(member);
                    }

                    String type = rs.getString("contact_type");
                    String value = rs.getString("contact_value");
                    if (type != null && value != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> contacts = (List<Map<String, String>>) member.get("contacts");
                        Map<String, String> contact = new HashMap<>();
                        contact.put("type", type);
                        contact.put("value", value);
                        contacts.add(contact);
                    }
                }

                // --------- Monitored Services ---------
                long serviceId = rs.getLong("monitored_service_id");
                if (!rs.wasNull() && !servicesMap.containsKey(serviceId)) {
                    Map<String, Object> service = new LinkedHashMap<>();
                    service.put("monitored_service_id", serviceId);
                    service.put("uuid", rs.getString("service_uuid"));
                    service.put("monitored_service_name", rs.getString("monitored_service_name"));
                    servicesMap.put(serviceId, service);
                    ((List<Map<String, Object>>) group.get("monitored_services")).add(service);
                }

                // --------- Notification Channels ---------
                long channelId = rs.getLong("notification_channel_id");
                if (!rs.wasNull() && !channelsMap.containsKey(channelId)) {
                    Map<String, Object> channel = new LinkedHashMap<>();
                    channel.put("notification_channel_id", channelId);
                    channel.put("notification_channel_code", rs.getString("notification_channel_code"));
                    channel.put("notification_channel_name", rs.getString("notification_channel_name"));
                    channelsMap.put(channelId, channel);
                    ((List<Map<String, Object>>) group.get("notification_channels")).add(channel);
                }
            }

            if (group == null) {
                ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Contact group not found");
                return;
            }

            ResponseUtil.sendSuccess(exchange, "Contact group fetched successfully", group);

        } catch (SQLException sqlEx) {
            logger.error("Database error fetching contact group: {}", sqlEx.getMessage(), sqlEx);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Database error while fetching contact group");
        } catch (Exception ex) {
            logger.error("Unexpected failure: {}", ex.getMessage(), ex);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Unexpected error occurred");
        }
    }
}
