package org.skypulse.handlers.logs;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.QueryUtil;
import org.skypulse.utils.ResponseUtil;

import java.sql.*;
import java.util.*;

import static org.skypulse.utils.DbUtil.setParams;

@RequireRoles({"ADMIN", "OPERATOR", "VIEWER"})
public class GetUptimeLogsHandler implements HttpHandler {

    private static final Map<String, String> SORT_MAP = Map.of(
            "checked", "checked_at",
            "service", "ms.monitored_service_name",
            "status", "status",
            "response", "response_time_ms"
    );

    private static final Map<String, String> FILTER_MAP = Map.of(
            "service", "ms.monitored_service_name",
            "status", "status"
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
                SELECT ul.uptime_log_id, ms.monitored_service_name,
                       ul.status, ul.response_time_ms, ul.http_status,
                       ul.error_message, ul.checked_at
                FROM uptime_logs ul
                LEFT JOIN monitored_services ms
                       ON ul.monitored_service_id = ms.monitored_service_id
                """ + qp.where() + qp.orderBy() + " LIMIT ? OFFSET ?";

        String countSql = """
                SELECT COUNT(*)
                FROM uptime_logs ul
                LEFT JOIN monitored_services ms
                       ON ul.monitored_service_id = ms.monitored_service_id
                """ + qp.where();

        List<Map<String, Object>> logs = new ArrayList<>();
        long total = 0;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            try (PreparedStatement st = conn.prepareStatement(countSql)) {
                setParams(st, qp.params());
                ResultSet rs = st.executeQuery();
                if (rs.next()) total = rs.getLong(1);
            }

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
}
