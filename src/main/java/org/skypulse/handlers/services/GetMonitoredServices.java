package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.ResponseUtil;

import java.util.*;
import java.util.stream.Collectors;

public class GetMonitoredServiceHandler implements HttpHandler {

    // Map short names to actual DB columns for filtering
    private static final Map<String, String> FILTERABLE_MAP = Map.of(
            "name", "monitored_service_name",
            "region", "monitored_service_region",
            "active", "is_active",
            "ssl", "ssl_enabled"
    );

    // Map short names to actual DB columns for sorting
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

    // Map column types for filtering
    private static final Map<String, Class<?>> COLUMN_TYPES = Map.of(
            "monitored_service_name", String.class,
            "monitored_service_region", String.class,
            "is_active", Boolean.class,
            "ssl_enabled", Boolean.class,
            "check_interval", Integer.class
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        Map<String, Deque<String>> params = exchange.getQueryParameters();

        // --- Parse query options using DatabaseUtils ---
        DatabaseUtils.QueryOptions opts = DatabaseUtils.parseQueryOptions(
                params,
                new HashSet<>(FILTERABLE_MAP.values()), // allowed filter columns
                new HashSet<>(SORTABLE_MAP.values()),   // allowed sort columns
                1, 10                                                         // default page and pageSize
        );

        // --- Build SQL ---
        String baseSql = "FROM monitored_services";
        String paginationSql = DatabaseUtils.buildPaginationSQL(opts, COLUMN_TYPES, new HashSet<>(SORTABLE_MAP.values()));
        String dataSql = "SELECT * " + baseSql + " " + paginationSql;
        String countSql = "SELECT COUNT(*) AS total " + baseSql + " " + DatabaseUtils.buildFiltersFromRules(opts.filters, COLUMN_TYPES, true).sql();

        // --- Combine params ---
        List<Object> dataParams = new ArrayList<>(DatabaseUtils.buildPaginationParams(opts, COLUMN_TYPES));
        dataParams.addAll(Arrays.asList(opts.pageSize, DatabaseUtils.calcOffset(opts.page, opts.pageSize))); // LIMIT/OFFSET

        // --- Execute queries ---
        int total = ((Number) DatabaseUtils.query(countSql, DatabaseUtils.buildPaginationParams(opts, COLUMN_TYPES))
                .get(0).get("total")).intValue();

        List<Map<String, Object>> services = DatabaseUtils.query(dataSql, dataParams);

        // --- Assign incremental IDs for client-side convenience ---
        int offset = DatabaseUtils.calcOffset(opts.page, opts.pageSize);
        for (int i = 0; i < services.size(); i++) {
            Map<String, Object> s = services.get(i);
            s.put("id", i + 1 + offset);
            s.remove("monitored_service_id");
        }

        // --- Send paginated response ---
        ResponseUtil.sendPaginated(exchange, "monitored services", opts.page, opts.pageSize, total, services);
    }
}
