package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;

import java.util.*;
import java.util.stream.Collectors;

public class GetMonitoredServices implements HttpHandler {

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

    private static final Map<String, Class<?>> COLUMN_TYPES = Map.of(
            "monitored_service_name", String.class,
            "monitored_service_region", String.class,
            "is_active", Boolean.class,
            "ssl_enabled", Boolean.class,
            "check_interval", Integer.class
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (HttpRequestUtil.dispatchIfIoThread(exchange, this)) return;


        Map<String, Deque<String>> params = exchange.getQueryParameters();

        int page = DatabaseUtils.parseIntParam(params.get("page"), 1);
        int pageSize = DatabaseUtils.parseIntParam(params.get("pageSize"), 10);
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

        String baseSql = "FROM monitored_services";
        String countSql = "SELECT COUNT(*) AS total " + baseSql + " WHERE 1=1 " + filterResult.sql();
        String dataSql = "SELECT * " + baseSql + " WHERE 1=1 "
                + filterResult.sql()
                + orderBy
                + " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(filterResult.params());
        dataParams.add(pageSize);
        dataParams.add(offset);

        List<Object> countParams = new ArrayList<>(filterResult.params());

        int total = ((Number) DatabaseUtils.query(countSql, countParams).getFirst().get("total")).intValue();
        List<Map<String, Object>> services = DatabaseUtils.query(dataSql, dataParams);


        ResponseUtil.sendPaginated(exchange, "monitored services", page, pageSize, total, services);
    }
}
