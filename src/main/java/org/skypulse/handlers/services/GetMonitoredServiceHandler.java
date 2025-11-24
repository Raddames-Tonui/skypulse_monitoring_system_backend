package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.ResponseUtil;

import java.util.*;

public class GetMonitoredServiceHandler implements HttpHandler {

    private static final Map<String, String> FILTERABLE_MAP = Map.of(
            "name", "monitored_service_name",
            "region", "monitored_service_region",
            "active", "is_active",
            "ssl", "ssl_enabled"
    );

    private static final Map<String, String> SORTABLE_MAP = Map.of(
            "name", "monitored_service_name",
            "url", "monitored_service_url",
            "region", "monitored_service_region",
            "interval", "check_interval",
            "created", "date_created",
            "checked", "last_checked",
            "active", "is_active",
            "status", "last_uptime_status"
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = exchange.getQueryParameters();

        // --- Pagination ---
        int page = DatabaseUtils.parseIntParam(params.get("page"), 1);
        int pageSize = DatabaseUtils.parseIntParam(params.get("pageSize"), 10);
        int offset = DatabaseUtils.calcOffset(page, pageSize);

        // --- Filters ---
        List<DatabaseUtils.FilterRule> filterRules = new ArrayList<>();
        for (String key : params.keySet()) {
            if (FILTERABLE_MAP.containsKey(key)) {
                String value = DatabaseUtils.getParam(params, key);
                if (value != null && !value.isEmpty()) {
                    filterRules.add(new DatabaseUtils.FilterRule(FILTERABLE_MAP.get(key), "=", value));
                }
            }
        }

        DatabaseUtils.FilterResult filters = buildPostgresFilters(filterRules);

        // --- Total count ---
        String countSql = "SELECT COUNT(*) AS total FROM monitored_services WHERE 1=1 " + filters.sql();
        List<Map<String, Object>> countResult = DatabaseUtils.query(countSql, filters.params());
        int total = ((Number) countResult.get(0).get("total")).intValue();

        // --- Sorting ---
        List<DatabaseUtils.SortRule> sortRules = parseSortRules(params.get("sortBy"));
        String orderBy = DatabaseUtils.buildOrderBy(sortRules, new HashSet<>(SORTABLE_MAP.values()));

        // --- Fetch paginated data ---
        String dataSql = "SELECT * FROM monitored_services WHERE 1=1 "
                + filters.sql()
                + orderBy
                + " LIMIT ? OFFSET ?";

        List<Object> dbParams = new ArrayList<>(filters.params());
        dbParams.add(pageSize);
        dbParams.add(offset);

        List<Map<String, Object>> services = DatabaseUtils.query(dataSql, dbParams);

        // --- Frontend-friendly IDs ---
        for (int i = 0; i < services.size(); i++) {
            Map<String, Object> s = services.get(i);
            s.put("id", i + 1 + offset);
            s.remove("monitored_service_id");
        }

        // --- Send response using ResponseUtil ---
        ResponseUtil.sendPaginated(exchange, "monitored services", page, pageSize, total, services);
    }

    private List<DatabaseUtils.SortRule> parseSortRules(Deque<String> sortParam) {
        List<DatabaseUtils.SortRule> rules = new ArrayList<>();
        if (sortParam != null && !sortParam.isEmpty()) {
            for (String s : sortParam.peekFirst().split(",")) {
                String[] parts = s.trim().split(" ");
                String shortColumn = parts[0];
                String direction = parts.length > 1 ? parts[1] : "asc";

                if (SORTABLE_MAP.containsKey(shortColumn)) {
                    rules.add(new DatabaseUtils.SortRule(SORTABLE_MAP.get(shortColumn), direction));
                }
            }
        }
        return rules;
    }

    private DatabaseUtils.FilterResult buildPostgresFilters(List<DatabaseUtils.FilterRule> rules) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        for (DatabaseUtils.FilterRule rule : rules) {
            // Auto-detect boolean, numeric, text
            if ("is_active".equals(rule.column) || "ssl_enabled".equals(rule.column)) {
                sql.append(" AND ").append(rule.column).append(" = ? ");
                params.add(Boolean.parseBoolean(rule.value));
            } else if (isNumeric(rule.value)) {
                sql.append(" AND ").append(rule.column).append(" = ? ");
                params.add(Integer.parseInt(rule.value));
            } else {
                sql.append(" AND ").append(rule.column).append(" ILIKE ? ");
                params.add("%" + rule.value + "%");
            }
        }

        return new DatabaseUtils.FilterResult(sql.toString(), params);
    }

    private boolean isNumeric(String str) {
        if (str == null) return false;
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
