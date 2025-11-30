package org.skypulse.handlers.company;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ListCompanies implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        int page = 1;
        int pageSize = 50;

        try (Connection conn = JdbcUtils.getConnection()) {
            int totalCount;
            try (PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM company");
                 ResultSet countRs = countPs.executeQuery()) {
                countRs.next();
                totalCount = countRs.getInt(1);
            }

            String sql = "SELECT company_id, company_name FROM company ORDER BY company_name LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, pageSize);
                ps.setInt(2, (page - 1) * pageSize);

                List<Map<String, Object>> companies = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        companies.add(Map.of(
                                "label", rs.getString("company_name"),
                                "value", rs.getInt("company_id")
                        ));
                    }
                }

                ResponseUtil.sendPaginated(exchange, "companies", page, pageSize, totalCount, companies);
            }

        } catch (Exception e) {
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                    "Failed to fetch companies: " + e.getMessage());
        }
    }
}
