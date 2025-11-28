package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@RequireRoles({"ADMIN", "OPERATOR"})
public class AddServicesToGroupHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(AddServicesToGroupHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        String groupUuidStr = exchange.getQueryParameters().get("uuid") != null ?
                exchange.getQueryParameters().get("uuid").getFirst() : null;
        if (groupUuidStr == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing group uuid");
            return;
        }

        UUID groupUuid;
        try {
            groupUuid = UUID.fromString(groupUuidStr);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid group uuid");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) return;

        List<Integer> serviceIds = (List<Integer>) body.get("serviceIds");
        if (serviceIds == null || serviceIds.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing or Empty serviceIds array");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            conn.setAutoCommit(false);
            long groupId;
            String groupSql = "SELECT contact_group_id FROM contact_groups WHERE uuid = ?";
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


            logger.info("Attempting to add {} services to group {} (UUID={}) by user {}", serviceIds.size(), groupId, groupUuid, ctx.email());

            Set<Integer> existingServices = new HashSet<>();
            String existingSql = "SELECT monitored_service_id FROM monitored_services_contact_groups WHERE contact_group_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(existingSql)) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existingServices.add(rs.getInt("monitored_service_id"));
                    }
                }
            }


            List<Integer> newServices = serviceIds.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !existingServices.contains(id))
                    .toList();

            if (newServices.isEmpty()) {
                ResponseUtil.sendSuccess(exchange, "No new services to add", null);
                return;
            }


            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO monitored_services_contact_groups (monitored_service_id, contact_group_id)
                VALUES (?, ?)
                ON CONFLICT (monitored_service_id, contact_group_id) DO NOTHING;
                """)) {
                for (Integer serviceId : newServices ) {
                    ps.setLong(1, serviceId);
                    ps.setLong(2, groupId);
                    ps.addBatch();
                }

                int[] results = ps.executeBatch();
                conn.commit();

                int inserted = (int) Arrays.stream(results).filter(r -> r >= 0).count();
                logger.info("Added {} new services to group {} (UUID={})", inserted, groupId, groupUuid);

                List<Map<String, Integer>> afterDataList = newServices.stream()
                        .filter(Objects::nonNull)
                        .map(id -> Map.of("service_id", id))
                        .toList().reversed();


                if (!afterDataList.isEmpty()) {
                    String afterDataJson = JsonUtil.mapper().writeValueAsString(afterDataList);
                    AuditLogger.log(
                            exchange,
                            "monitored_services_contact_groups",
                            groupId,
                            "CREATE",
                            null,
                            afterDataJson
                    );
                }
                ResponseUtil.sendCreated(exchange, inserted + " services added successfully", null);
            }
        }catch (Exception e) {
            logger.error("Failed to add services to group (UUID={}): {}", groupUuid, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }

    }
}
