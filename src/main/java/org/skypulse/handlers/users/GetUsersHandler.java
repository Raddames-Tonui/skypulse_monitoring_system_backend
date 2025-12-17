package org.skypulse.handlers.users;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.ResponseUtil;

import java.util.*;

@RequireRoles({"ADMIN"})
public class GetUsersHandler implements HttpHandler {

    private static final Map<String, String> FILTERABLE_MAP = Map.of(
            "first_name", "u.first_name",
            "last_name", "u.last_name",
            "email", "u.user_email",
            "role", "r.role_name",
            "company", "c.company_name",
            "active", "u.is_active"
    );

    private static final Map<String, String> SORTABLE_MAP = Map.of(
            "first_name", "u.first_name",
            "last_name", "u.last_name",
            "email", "u.user_email",
            "role", "r.role_name",
            "company", "c.company_name",
            "active", "u.is_active",
            "created", "u.date_created",
            "modified", "u.date_modified"
    );

    private static final Map<String, Class<?>> COLUMN_TYPES = Map.of(
            "u.first_name", String.class,
            "u.last_name", String.class,
            "u.user_email", String.class,
            "r.role_name", String.class,
            "c.company_name", String.class,
            "u.is_active", Boolean.class
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

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
                filterRules.add(new DatabaseUtils.FilterRule(dbColumn, "=", value));
            }
        }
        DatabaseUtils.FilterResult filterResult = DatabaseUtils.buildFiltersFromRules(filterRules, COLUMN_TYPES, true);

        List<DatabaseUtils.SortRule> sortRules = new ArrayList<>();
        Deque<String> sortParam = params.get("sort");
        if (sortParam != null && !sortParam.isEmpty()) {
            for (String s : sortParam.peekFirst().split(",")) {
                String[] parts = s.trim().split(":");
                String shortCol = parts[0];
                String dir = parts.length > 1 ? parts[1] : "asc";
                if (SORTABLE_MAP.containsKey(shortCol)) {
                    sortRules.add(new DatabaseUtils.SortRule(SORTABLE_MAP.get(shortCol), dir));
                }
            }
        }
        String orderBy = DatabaseUtils.buildOrderBy(sortRules, new HashSet<>(SORTABLE_MAP.values()));

        String baseSql = """
            FROM users u
            LEFT JOIN roles r ON u.role_id = r.role_id
            LEFT JOIN company c ON u.company_id = c.company_id
        """;

        String countSql = "SELECT COUNT(*) AS total " + baseSql + " WHERE 1=1 " + filterResult.sql();
        String dataSql = """
            SELECT u.user_id, u.uuid, u.first_name, u.last_name, u.user_email,
                   u.is_active, u.date_created, u.date_modified,
                   r.role_id, r.role_name,
                   c.company_id, c.company_name
            """ + baseSql + " WHERE 1=1 " + filterResult.sql() + orderBy + " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(filterResult.params());
        dataParams.add(pageSize);
        dataParams.add(offset);
        List<Object> countParams = new ArrayList<>(filterResult.params());

        int total = ((Number) DatabaseUtils.query(countSql, countParams).getFirst().get("total")).intValue();
        List<Map<String, Object>> users = DatabaseUtils.query(dataSql, dataParams);


        ResponseUtil.sendPaginated(exchange, "users", page, pageSize, total, users);
    }
}
