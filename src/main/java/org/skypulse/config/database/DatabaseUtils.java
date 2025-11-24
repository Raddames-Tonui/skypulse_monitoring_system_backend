package org.skypulse.config.database;

import java.sql.*;
import java.util.*;

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


    // Detect text-like columns
    private static boolean isTextColumn(String column) {
        return column.contains("name") || column.contains("region") || column.contains("status") || column.contains("url");
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
        return sb.toString();
    }

    // --- Query helper using JdbcUtils ---
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
}
