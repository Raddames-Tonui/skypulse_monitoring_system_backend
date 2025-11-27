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

@RequireRoles({"ADMIN"})
public class GetContactGroupsHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GetContactGroupsHandler.class);

    private static final String QUERY = """
        SELECT
            cg.contact_group_id,
            cg.uuid,
            cg.contact_group_name,
            cg.contact_group_description,
            cg.date_modified,

            COUNT(DISTINCT cgm.user_id) AS members_count,
            COUNT(DISTINCT ms_cg.monitored_service_id) AS services_count

        FROM contact_groups cg

        LEFT JOIN contact_group_members cgm
            ON cg.contact_group_id = cgm.contact_group_id

        LEFT JOIN monitored_services_contact_groups ms_cg
            ON cg.contact_group_id = ms_cg.contact_group_id

        WHERE cg.is_deleted = FALSE
        GROUP BY cg.contact_group_id
        ORDER BY cg.contact_group_name;
        """;

    @Override
    public void handleRequest(HttpServerExchange exchange) {

        try (Connection conn = DatabaseManager.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(QUERY);
             ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> groups = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> g = new LinkedHashMap<>();

                g.put("contact_group_id", rs.getLong("contact_group_id"));
                g.put("uuid", rs.getString("uuid"));
                g.put("contact_group_name", rs.getString("contact_group_name"));
                g.put("contact_group_description", rs.getString("contact_group_description"));
                g.put("date_modified", rs.getTimestamp("date_modified"));

                g.put("members_count", rs.getInt("members_count"));
                g.put("services_count", rs.getInt("services_count"));

                g.put("actions", Map.of(
                        "view", "/contacts/groups/" + rs.getString("uuid"),
                        "edit", "/contacts/groups/" + rs.getString("uuid") + "/edit"
                ));

                groups.add(g);
            }

            ResponseUtil.sendSuccess(exchange,
                    "Contact group summaries fetched", groups);

        } catch (SQLException sqlEx) {
            logger.error("Database error: {}", sqlEx.getMessage(), sqlEx);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Database error while fetching contact groups");
        } catch (Exception ex) {
            logger.error("Unexpected failure: {}", ex.getMessage(), ex);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Unexpected server error");
        }
    }
}
