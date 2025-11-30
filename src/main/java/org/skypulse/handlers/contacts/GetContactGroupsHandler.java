package org.skypulse.handlers.contacts;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;

import java.util.*;
import java.sql.*;

@RequireRoles({"ADMIN"})
public class GetContactGroupsHandler implements HttpHandler {

    private static final Map<String, String> FILTERABLE_MAP = Map.of(
            "contact_group_name", "cg.contact_group_name",
            "is_deleted", "cg.is_deleted"
    );

    private static final Map<String, String> SORTABLE_MAP = Map.of(
            "contact_group_name", "cg.contact_group_name",
            "date_modified", "cg.date_modified",
            "members_count", "members_count",
            "services_count", "services_count",
            "is_deleted", "cg.is_deleted"
    );

    private static final Map<String, Class<?>> COLUMN_TYPES = Map.of(
            "cg.contact_group_name", String.class,
            "cg.is_deleted", Boolean.class
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        if (HttpRequestUtil.dispatchIfIoThread(exchange, this)) return;

        Map<String, Deque<String>> params = exchange.getQueryParameters();

        int page = DatabaseUtils.parseIntParam(params.get("page"), 1);
        int pageSize = DatabaseUtils.parseIntParam(params.get("pageSize"), 20);
        int offset = DatabaseUtils.calcOffset(page, pageSize);

        List<DatabaseUtils.FilterRule> filterRules = new ArrayList<>();
        for (Map.Entry<String, String> entry : FILTERABLE_MAP.entrySet()) {
            String shortName = entry.getKey();
            String dbColumn = entry.getValue();
            String value = DatabaseUtils.getParam(params, shortName);

            if (value != null && !value.isBlank()) {
                if ("is_deleted".equals(shortName)) {
                    filterRules.add(new DatabaseUtils.FilterRule(dbColumn, "=", Boolean.parseBoolean(value) + ""));
                } else {
                    filterRules.add(new DatabaseUtils.FilterRule(dbColumn, "=", value));
                }
            }
        }
        DatabaseUtils.FilterResult filterResult = DatabaseUtils.buildFiltersFromRules(filterRules, COLUMN_TYPES, true);

        List<DatabaseUtils.SortRule> sortRules = new ArrayList<>();
        Deque<String> sortParam = params.get("sort");
        if (sortParam != null && !sortParam.isEmpty()) {
            String[] parts = sortParam.peekFirst().split(",");
            for (String p : parts) {
                String[] s = p.trim().split(":");
                String shortName = s[0];
                String dir = s.length > 1 ? s[1] : "asc";
                if (SORTABLE_MAP.containsKey(shortName)) {
                    sortRules.add(new DatabaseUtils.SortRule(SORTABLE_MAP.get(shortName), dir));
                }
            }
        }
        String orderBy = DatabaseUtils.buildOrderBy(sortRules, new HashSet<>(SORTABLE_MAP.values()));

        String baseSql = """
            FROM contact_groups cg
            LEFT JOIN contact_group_members cgm ON cg.contact_group_id = cgm.contact_group_id
            LEFT JOIN monitored_services_contact_groups ms_cg ON cg.contact_group_id = ms_cg.contact_group_id
        """;

        String countSql = "SELECT COUNT(*) AS total " + baseSql + " WHERE 1=1 " + filterResult.sql();
        List<Object> countParams = filterResult.params();
        int total = ((Number) DatabaseUtils.query(countSql, countParams).get(0).get("total")).intValue();

        String dataSql = "SELECT " +
                "cg.contact_group_id AS contact_group_id, " +
                "cg.uuid AS uuid, " +
                "cg.contact_group_name AS contact_group_name, " +
                "cg.contact_group_description AS contact_group_description, " +
                "cg.date_modified AS date_modified, " +
                "COUNT(DISTINCT cgm.user_id) AS members_count, " +
                "COUNT(DISTINCT ms_cg.monitored_service_id) AS services_count, " +
                "cg.is_deleted AS is_deleted " +
                baseSql +
                " WHERE 1=1 " + filterResult.sql() +
                " GROUP BY cg.contact_group_id " + orderBy +
                " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(filterResult.params());
        dataParams.add(pageSize);
        dataParams.add(offset);

        List<Map<String, Object>> groups = DatabaseUtils.query(dataSql, dataParams);

        ResponseUtil.sendPaginated(exchange, "contact_groups", page, pageSize, total, groups);
    }
}
