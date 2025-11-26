package org.skypulse.config.database;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class to handle database operations with pagination, filtering, and sorting.
 */
public final class DatabaseUtils {

    private DatabaseUtils() {} // static-only utility class

    // --- Pagination helpers ---
    public static int parseIntParam(Deque<String> param, int defaultValue) {
        if (param == null || param.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(param.peekFirst());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int calcOffset(int page, int pageSize) {
        return Math.max(0, (page - 1) * pageSize);
    }

    // --- Filter handling ---
    public static class FilterRule {
        public final String column;
        public final String operator;
        public final String value;

        public FilterRule(String column, String operator, String value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }

    public static class FilterResult {
        private final String sql;
        private final List<Object> params;

        public FilterResult(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }

        public String sql() { return sql; }
        public List<Object> params() { return params; }
    }

    public static FilterResult buildFiltersFromRules(List<FilterRule> rules, Map<String, Class<?>> columnTypes, boolean ignoreInvalid) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        for (FilterRule rule : rules) {
            Class<?> type = columnTypes.get(rule.column);
            if (type == null) {
                if (ignoreInvalid) continue;
                throw new IllegalArgumentException("Invalid filter column: " + rule.column);
            }

            if (type == String.class) {
                sql.append(" AND ").append(rule.column).append(" ILIKE ? ");
                params.add("%" + rule.value + "%");
            } else if (type == Boolean.class) {
                sql.append(" AND ").append(rule.column).append(" = ? ");
                params.add(Boolean.parseBoolean(rule.value));
            } else if (Number.class.isAssignableFrom(type)) {
                sql.append(" AND ").append(rule.column).append(" = ? ");
                if (type == Integer.class) params.add(Integer.parseInt(rule.value));
                else if (type == Long.class) params.add(Long.parseLong(rule.value));
                else if (type == Double.class) params.add(Double.parseDouble(rule.value));
                else params.add(rule.value);
            } else {
                sql.append(" AND ").append(rule.column).append(" = ? ");
                params.add(rule.value);
            }
        }

        return new FilterResult(sql.toString(), params);
    }

    // --- Sorting handling ---
    public static class SortRule {
        public final String column;
        public final String direction;

        public SortRule(String column, String direction) {
            this.column = column;
            this.direction = direction;
        }
    }

    public static String buildOrderBy(List<SortRule> sortRules, Set<String> allowedColumns) {
        if (sortRules == null || sortRules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;

        for (SortRule r : sortRules) {
            if (!allowedColumns.contains(r.column)) continue;
            if (!first) sb.append(", ");
            sb.append(r.column).append(" ").append(r.direction.equalsIgnoreCase("desc") ? "DESC" : "ASC");
            first = false;
        }
        return first ? "" : sb.toString();
    }

    // --- Query helper ---
    public static List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            int columns = md.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(md.getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
        }
        return result;
    }

    // --- URL param parsing ---
    public static String getParam(Map<String, Deque<String>> params, String key) {
        Deque<String> val = params.get(key);
        return val != null && !val.isEmpty() ? val.peekFirst() : null;
    }

    // --- Build filter rules from request params ---
    public static List<FilterRule> parseFilters(Map<String, Deque<String>> params, Set<String> allowedColumns) {
        List<FilterRule> rules = new ArrayList<>();
        for (Map.Entry<String, Deque<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!allowedColumns.contains(key)) continue;
            String value = getParam(params, key);
            if (value != null && !value.isBlank()) {
                rules.add(new FilterRule(key, "=", value));
            }
        }
        return rules;
    }

    // --- Build sort rules from "sort" param ---
    public static List<SortRule> parseSort(String sortParam, Set<String> allowedColumns) {
        if (sortParam == null || sortParam.isBlank()) return Collections.emptyList();
        String[] parts = sortParam.split(",");
        List<SortRule> sortRules = new ArrayList<>();
        for (String part : parts) {
            String[] p = part.split(":");
            String col = p[0].trim();
            String dir = p.length > 1 ? p[1].trim() : "asc";
            if (allowedColumns.contains(col)) {
                sortRules.add(new SortRule(col, dir));
            }
        }
        return sortRules;
    }

    // --- Convenience method for full SQL query ---
    public static class QueryOptions {
        public final int page;
        public final int pageSize;
        public final List<FilterRule> filters;
        public final List<SortRule> sorts;

        public QueryOptions(int page, int pageSize, List<FilterRule> filters, List<SortRule> sorts) {
            this.page = page;
            this.pageSize = pageSize;
            this.filters = filters;
            this.sorts = sorts;
        }
    }

    public static QueryOptions parseQueryOptions(Map<String, Deque<String>> params,
                                                 Set<String> allowedFilterColumns,
                                                 Set<String> allowedSortColumns,
                                                 int defaultPage,
                                                 int defaultPageSize) {

        int page = parseIntParam(params.get("page"), defaultPage);
        int pageSize = parseIntParam(params.get("pageSize"), defaultPageSize);
        List<FilterRule> filters = parseFilters(params, allowedFilterColumns);
        List<SortRule> sorts = parseSort(getParam(params, "sort"), allowedSortColumns);

        return new QueryOptions(page, pageSize, filters, sorts);
    }

    public static String buildPaginationSQL(QueryOptions opts, Map<String, Class<?>> columnTypes, Set<String> allowedSortColumns) {
        FilterResult filterResult = buildFiltersFromRules(opts.filters, columnTypes, true);
        String orderBy = buildOrderBy(opts.sorts, allowedSortColumns);
        int offset = calcOffset(opts.page, opts.pageSize);

        return " WHERE 1=1 " + filterResult.sql() + orderBy + " LIMIT " + opts.pageSize + " OFFSET " + offset;
    }

    public static List<Object> buildPaginationParams(QueryOptions opts, Map<String, Class<?>> columnTypes) {
        FilterResult filterResult = buildFiltersFromRules(opts.filters, columnTypes, true);
        return filterResult.params();
    }
}
