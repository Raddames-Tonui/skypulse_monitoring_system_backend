package org.skypulse.utils;

import io.undertow.server.HttpServerExchange;

import java.util.*;

/**
 * Pagination → page, pageSize, offset
 * Filtering → dynamic filters mapped from URL keys to DB columns
 * Date range filters → from, to
 * Sorting → multiple sort keys using ?sort=name:asc,region:desc
 * Case-insensitive string filters → automatically normalizes DB+input using LOWER(column) LIKE LOWER(?)
 * */
public class QueryUtil {

    public record QueryParts(
            String where,
            String orderBy,
            List<Object> params,
            int page,
            int pageSize,
            int offset
    ) {}

    public static QueryParts build(
            HttpServerExchange exchange,
            Map<String, String> filterMap,
            Map<String, String> sortMap,
            String defaultSortColumn
    ) {

        Map<String, Deque<String>> q = exchange.getQueryParameters();

        int page = parseInt(q.get("page"), 1);
        int pageSize = parseInt(q.get("pageSize"), 20);
        int offset = (page - 1) * pageSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        for (var entry : filterMap.entrySet()) {
            String urlKey = entry.getKey();
            String dbColumn = entry.getValue();

            if (q.containsKey(urlKey)) {
                String raw = q.get(urlKey).getFirst();

                boolean isNumeric = raw.matches("\\d+");
                boolean isBoolean = raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false");

                if (isNumeric || isBoolean) {
                    // numeric / boolean keep normal comparison
                    where.append(" AND ").append(dbColumn).append(" = ? ");
                    params.add(raw);
                } else {
                    // TEXT → make case-insensitive
                    where.append(" AND LOWER(").append(dbColumn).append(") = LOWER(?) ");
                    params.add(raw);
                }
            }
        }

        // Date range (kept as-is)
        if (q.containsKey("from")) {
            where.append(" AND checked_at >= ? ");
            params.add(q.get("from").getFirst());
        }
        if (q.containsKey("to")) {
            where.append(" AND checked_at <= ? ");
            params.add(q.get("to").getFirst());
        }

        // --- CASE-INSENSITIVE SORTING ---
        StringBuilder orderBy = new StringBuilder(" ORDER BY " + defaultSortColumn + " DESC ");

        if (q.containsKey("sort")) {
            String sortParam = q.get("sort").getFirst();
            String[] parts = sortParam.split(",");

            List<String> clauses = new ArrayList<>();

            for (String p : parts) {
                if (!p.contains(":")) continue;

                String[] s = p.split(":");
                String key = s[0].trim().toLowerCase();
                String direction = s[1].equalsIgnoreCase("asc") ? "ASC" : "DESC";

                if (sortMap.containsKey(key)) {
                    clauses.add(sortMap.get(key) + " " + direction);
                }
            }

            if (!clauses.isEmpty()) {
                orderBy = new StringBuilder(" ORDER BY " + String.join(", ", clauses));
            }
        }

        return new QueryParts(where.toString(), orderBy.toString(), params, page, pageSize, offset);
    }

    private static int parseInt(Deque<String> d, int fallback) {
        try {
            return d == null ? fallback : Integer.parseInt(d.getFirst());
        } catch (Exception e) {
            return fallback;
        }
    }
}
