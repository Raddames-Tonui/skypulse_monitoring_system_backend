package org.skypulse.config.database;

import java.sql.*;
import java.util.*;
import java.util.Deque;

public final class DatabaseUtil {

    private DatabaseUtil() {}

    // -------- Parameter Helpers --------

    public static String getParam(Map<String, Deque<String>> params, String key) {
        Deque<String> deque = params.get(key);
        return (deque == null) ? null : deque.peekFirst();
    }

    public static int parseIntParam(Deque<String> deque, int defaultValue) {
        try {
            return (deque == null) ? defaultValue : Integer.parseInt(deque.peekFirst());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -------- Sorting Helper --------
    public static String buildOrderBy(String sortParam, Set<String> allowedColumns) {
        if (sortParam == null || sortParam.isBlank()) {
            return " ORDER BY date_created DESC ";
        }

        String[] entries = sortParam.split(",");
        List<String> orderSql = new ArrayList<>();

        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;

            String column = parts[0].trim();
            String direction = parts[1].trim().toUpperCase();

            if (!allowedColumns.contains(column)) continue;
            if (!direction.equals("ASC") && !direction.equals("DESC")) direction = "ASC";

            orderSql.add(column + " " + direction);
        }

        if (orderSql.isEmpty()) return " ORDER BY date_created DESC ";

        return " ORDER BY " + String.join(", ", orderSql) + " ";
    }

    // -------- Pagination Helper --------
    public static int calcOffset(int page, int size) {
        return Math.max(page - 1, 0) * size;
    }

    // -------- Dynamic Filtering Helper --------
    public static FilterResult buildFilters(Map<String, Deque<String>> params, Set<String> allowedColumns) {
        StringBuilder sql = new StringBuilder();
        List<Object> dbParams = new ArrayList<>();

        for (String column : allowedColumns) {
            String value = getParam(params, column);
            if (value == null || value.isBlank()) continue;

            // Auto-detect boolean
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                sql.append(" AND ").append(column).append(" = ? ");
                dbParams.add(Boolean.parseBoolean(value));
            }
            // Numeric value
            else if (value.matches("\\d+")) {
                sql.append(" AND ").append(column).append(" = ? ");
                dbParams.add(Integer.parseInt(value));
            }
            // Otherwise treat as string (ILIKE)
            else {
                sql.append(" AND ").append(column).append(" ILIKE ? ");
                dbParams.add("%" + value + "%");
            }
        }

        return new FilterResult(sql.toString(), dbParams);
    }

    public static class FilterResult {
        public final String sql;
        public final List<Object> params;

        public FilterResult(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }

    // -------- Query Execution --------
    public static List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException {
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    results.add(row);
                }

                return results;
            }
        }
    }
}
