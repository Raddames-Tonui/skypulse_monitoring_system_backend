package org.skypulse.handlers.logs;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GetAuditLogsHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GetAuditLogsHandler.class);

    private static final Map<String, String> FILTERABLE_MAP = Map.of(
            "entity", "a.entity",
            "action", "a.action"
    );

    private static final Map<String, String> SORTABLE_MAP = Map.of(
            "date", "a.date_created",
            "entity", "a.entity",
            "user", "u.first_name",
            "action", "a.action"
    );

    private static final Map<String, Class<?>> COLUMN_TYPES = Map.of(
            "a.entity", String.class,
            "a.action", String.class,
            "a.date_created", java.sql.Timestamp.class
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        if (HttpRequestUtil.dispatchIfIoThread(exchange, this)) return;

        Map<String, Deque<String>> params = exchange.getQueryParameters();

        int page = DatabaseUtils.parseIntParam(params.get("page"), 1);
        int pageSize = DatabaseUtils.parseIntParam(params.get("pageSize"), 15);
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

        String baseSql = " FROM audit_log a LEFT JOIN users u ON a.user_id = u.user_id ";

        String countSql = "SELECT COUNT(*) AS total " + baseSql + " WHERE 1=1 " + filterResult.sql();
        List<Object> countParams = filterResult.params();
        int total = ((Number) DatabaseUtils.query(countSql, countParams).get(0).get("total")).intValue();

        String dataSql = "SELECT " +
                "a.audit_log_id, a.user_id, a.entity, a.entity_id, a.action, " +
                "a.before_data, a.after_data, a.ip_address, a.date_created, " +
                "u.user_email, CONCAT(u.first_name, ' ', u.last_name) AS user_full_name " +
                baseSql + " WHERE 1=1 " + filterResult.sql() +
                orderBy + " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(filterResult.params());
        dataParams.add(pageSize);
        dataParams.add(offset);

        List<Map<String, Object>> logs = DatabaseUtils.query(dataSql, dataParams);

        // Parse before_data and after_data JSON strings into objects
        for (Map<String, Object> log : logs) {
            Object beforeDataObj = log.get("before_data");
            Object afterDataObj = log.get("after_data");

            if (beforeDataObj instanceof String) {
                try {
                    Map<String, Object> beforeDataMap = JsonUtil.mapper().readValue(
                            (String) beforeDataObj, new TypeReference<Map<String, Object>>() {});
                    log.put("before_data", beforeDataMap);
                } catch (Exception e) {
                    logger.error("Failed to parse before_data for audit_log_id {}: {}", log.get("audit_log_id"), e.getMessage(), e);
                }
            }

            if (afterDataObj instanceof String) {
                try {
                    Map<String, Object> afterDataMap = JsonUtil.mapper().readValue(
                            (String) afterDataObj, new TypeReference<Map<String, Object>>() {});
                    log.put("after_data", afterDataMap);
                } catch (Exception e) {
                    logger.error("Failed to parse after_data for audit_log_id {}: {}", log.get("audit_log_id"), e.getMessage(), e);
                }
            }
        }

        ResponseUtil.sendPaginated(
                exchange,
                "audit logs",
                page,
                pageSize,
                total,
                logs
        );
    }
}
