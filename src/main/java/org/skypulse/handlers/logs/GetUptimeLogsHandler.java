package org.skypulse.handlers.logs;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.QueryUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.*;
import java.util.*;


public class GetUptimeLogsHandler implements HttpHandler {

    private static final Map<String, String> SORT_MAP = Map.of(
            "checked", "checked_at",
            "service", "monitored_service_id",
            "status", "status",
            "response", "response_time_ms",
            "code", "http_status",
            "region", "region"
    );

    private static final Map<String, String> FILTER_MAP = Map.of(
            "service", "monitored_service_id",
            "status", "status",
            "region", "region",
            "code", "http_status"
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        QueryUtil.QueryParts qp = QueryUtil.build(
                exchange,
                FILTER_MAP,
                SORT_MAP,
                "checked_at"
        );

        String sql = """
                        SELECT ms.monitored_service_name, ul.status, ul.response_time_ms, ul.http_status,
                               ul.error_message, ul.region, ul.checked_at
                        FROM uptime_logs ul
                        LEFT JOIN monitored_services ms
                               ON ul.monitored_service_id = ms.monitored_service_id
                    """ + qp.where() + qp.orderBy() + " LIMIT ? OFFSET ?";


        String countSql = """
                                SELECT COUNT(*)
                                FROM uptime_logs
                            """ + qp.where();


        List<Map<String, Object>> logs = new ArrayList<>();
        long total = 0;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // count
            try (PreparedStatement st = conn.prepareStatement(countSql)) {
                setParams(st, qp.params());
                ResultSet rs = st.executeQuery();
                if (rs.next()) total = rs.getLong(1);
            }

            // data
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                int idx = setParams(st, qp.params());
                st.setInt(idx++, qp.pageSize());
                st.setInt(idx, qp.offset());

                ResultSet rs = st.executeQuery();
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    logs.add(row);
                }
            }
        }

        ResponseUtil.sendPaginated(exchange, "uptime_logs",
                qp.page(), qp.pageSize(), (int) total, logs);
    }

    private int setParams(PreparedStatement st, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            st.setObject(i++, p);
        }
        return i;
    }
}
