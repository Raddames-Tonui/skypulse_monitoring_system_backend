package org.skypulse.config.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class to handle database operations with pagination, filtering, sorting, and JSONB support.
 */
public final class DatabaseUtils {

    private DatabaseUtils() {}

    private static final ObjectMapper mapper = new ObjectMapper();

    //  Pagination helpers
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

    //  Filter handling
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

    //  Sorting handling
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

    //  Query helper with JSONB support
    public static List<Map<String, Object>> query(String sql, List<Object> params) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();

        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
            }

            ResultSet rs = stmt.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            int columns = md.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = rs.getObject(i);

                    // Automatically parse JSONB columns
                    if (value instanceof PGobject pg && "jsonb".equalsIgnoreCase(pg.getType())) {
                        if (pg.getValue() != null) {
                            try {
                                row.put(md.getColumnName(i), mapper.readValue(pg.getValue(), Map.class));
                            } catch (Exception e) {
                                // fallback to raw JSON string if parsing fails
                                row.put(md.getColumnName(i), pg.getValue());
                            }
                        } else {
                            row.put(md.getColumnName(i), null);
                        }
                    } else {
                        row.put(md.getColumnName(i), value);
                    }
                }
                result.add(row);
            }
        }
        return result;
    }

    //  URL param parsing
    public static String getParam(Map<String, Deque<String>> params, String key) {
        Deque<String> val = params.get(key);
        return val != null && !val.isEmpty() ? val.peekFirst() : null;
    }







}
