package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtil;
import org.skypulse.utils.ResponseUtil;

import java.util.*;

public class GetMonitoredServiceHandler implements HttpHandler {

    private static final Set<String> FILTERABLE_COLUMNS = Set.of(
            "monitored_service_name",
            "monitored_service_url",
            "monitored_service_region",
            "check_interval",
            "is_active"
    );

    private static final Set<String> SORTABLE_COLUMNS = Set.of(
            "monitored_service_name",
            "monitored_service_url",
            "monitored_service_region",
            "check_interval",
            "date_created",
            "last_checked",
            "is_active",
            "last_uptime_status"
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = exchange.getQueryParameters();

        int page = DatabaseUtil.parseIntParam(params.get("page"), 1);
        int size = DatabaseUtil.parseIntParam(params.get("size"), 10);
        int offset = DatabaseUtil.calcOffset(page, size);

        DatabaseUtil.FilterResult filters = DatabaseUtil.buildFilters(params, FILTERABLE_COLUMNS);

        String countSql = "SELECT COUNT(*) AS total FROM monitored_services WHERE 1=1 " + filters.sql;
        List<Map<String, Object>> countResult = DatabaseUtil.query(countSql, filters.params);
        int total = ((Number) countResult.get(0).get("total")).intValue();

        String sortParam = DatabaseUtil.getParam(params, "sort");
        String orderBy = DatabaseUtil.buildOrderBy(sortParam, SORTABLE_COLUMNS);

        String dataSql = "SELECT * FROM monitored_services WHERE 1=1 "
                + filters.sql
                + orderBy
                + " LIMIT ? OFFSET ?";

        List<Object> dbParams = new ArrayList<>(filters.params);
        dbParams.add(size);
        dbParams.add(offset);

        List<Map<String, Object>> services = DatabaseUtil.query(dataSql, dbParams);

        ResponseUtil.sendPaginated(exchange, page, size, total, services, "services");
    }
}
