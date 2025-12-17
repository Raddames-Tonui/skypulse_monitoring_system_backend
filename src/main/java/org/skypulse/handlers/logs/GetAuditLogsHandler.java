package org.skypulse.handlers.logs;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


/**
 * Parse JSONB field dynamically: converts JSON objects to Map and JSON arrays to List<Map>.
 * */

@RequireRoles({"ADMIN"})
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

    private static final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

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
        int total = ((Number) DatabaseUtils.query(countSql, filterResult.params()).getFirst().get("total")).intValue();

        String dataSql = """
        SELECT a.audit_log_id, a.user_id, a.entity, a.entity_id, a.action,
               a.before_data, a.after_data, a.ip_address, a.date_created,
               u.user_email, CONCAT(u.first_name, ' ', u.last_name) AS user_full_name
        """ + baseSql + " WHERE 1=1 " + filterResult.sql() + orderBy + " LIMIT ? OFFSET ?";


        List<Object> dataParams = new ArrayList<>(filterResult.params());
        dataParams.add(pageSize);
        dataParams.add(offset);

        List<Map<String, Object>> logs = DatabaseUtils.query(dataSql, dataParams);

        // Deserialize JSONB fields automatically detecting objects vs arrays
        for (Map<String, Object> log : logs) {
            parseJsonField(log, "before_data");
            parseJsonField(log, "after_data");
        }

        ResponseUtil.sendPaginated(exchange, "audit_logs", page, pageSize, total, logs);
    }


    private void parseJsonField(Map<String, Object> log, String fieldName) {
        Object obj = log.get(fieldName);
        if (obj instanceof String) {
            try {
                String jsonStr = (String) obj;
                if (jsonStr.trim().startsWith("[")) {
                    // JSON Array
                    List<Map<String, Object>> list = mapper.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {});
                    log.put(fieldName, list != null ? list : Collections.emptyList());
                } else {
                    // JSON Object
                    Map<String, Object> map = mapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
                    log.put(fieldName, map != null ? map : Collections.emptyMap());
                }
            } catch (Exception e) {
                logger.error("Failed to parse {} for audit_log_id {}: {}", fieldName, log.get("audit_log_id"), e.getMessage(), e);
                log.put(fieldName, jsonStrIsArray(obj) ? Collections.emptyList() : Collections.emptyMap());
            }
        } else if (obj == null) {
            log.put(fieldName, Collections.emptyMap());
        }
    }

    private boolean jsonStrIsArray(Object obj) {
        if (!(obj instanceof String)) return false;
        return ((String) obj).trim().startsWith("[");
    }
}
