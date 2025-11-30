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
public class GetSSLLogsHandler implements HttpHandler {

    private static final Map<String, String> SORT_MAP = Map.of(
            "checked", "last_checked",
            "service", "ms.monitored_service_name",
            "domain", "domain",
            "issuer", "issuer",
            "expiry", "expiry_date",
            "days_remaining", "days_remaining",
            "chain_valid", "chain_valid"
    );

    private static final Map<String, String> FILTER_MAP = Map.of(
            "service", "ms.monitored_service_name",
            "domain", "domain",
            "issuer", "issuer",
            "chain_valid", "chain_valid"
    );

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        QueryUtil.QueryParts qp = QueryUtil.build(exchange, FILTER_MAP, SORT_MAP, "last_checked");

        String sql = """
            SELECT s.ssl_log_id, s.monitored_service_id, ms.monitored_service_name AS service_name,
                   s.domain, s.issuer, s.serial_number,
                   s.signature_algorithm, s.public_key_algo, s.public_key_length, s.san_list, s.chain_valid,
                   s.subject, s.fingerprint, s.issued_date, s.expiry_date, s.days_remaining, s.last_checked
            FROM ssl_logs s
            LEFT JOIN monitored_services ms ON s.monitored_service_id = ms.monitored_service_id
        """ + qp.where() + qp.orderBy() + " LIMIT ? OFFSET ?";

        String countSql = """
            SELECT COUNT(*)
            FROM ssl_logs s
            LEFT JOIN monitored_services ms ON s.monitored_service_id = ms.monitored_service_id
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
                        String colName = md.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        row.put(colName, value);
                    }

                    if (row.get("issuer") != null) {
                        Map<String, String> issuerInfo = parseIssuer(row.get("issuer").toString());
                        row.put("issuer_common_name", issuerInfo.get("CN"));
                        row.put("issuer_org", issuerInfo.get("O"));
                        row.put("issuer_country", issuerInfo.get("C"));
                    }

                    logs.add(row);
                }
            }
        }

        ResponseUtil.sendPaginated(exchange, "ssl_logs", qp.page(), qp.pageSize(), (int) total, logs);
    }


    /**
     * Parses issuer string like:
     * CN=DigiCert Global G2 TLS RSA SHA256 2020 CA1,O=DigiCert Inc,C=US
     */
    private Map<String, String> parseIssuer(String issuer) {
        Map<String, String> map = new HashMap<>();
        String[] parts = issuer.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }
}
