package org.skypulse.handlers.reports;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.ResponseUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class GenerateSslPdfReports implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GenerateSslPdfReports.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) {

        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String serviceIdParam = DatabaseUtils.getParam(exchange.getQueryParameters(), "service_id");
        String filterPeriod = DatabaseUtils.getParam(exchange.getQueryParameters(), "period");
        String statusFilter = DatabaseUtils.getParam(exchange.getQueryParameters(), "status");
        String downloadParam = DatabaseUtils.getParam(exchange.getQueryParameters(), "download");
        boolean forceDownload = "1".equals(downloadParam) || "true".equalsIgnoreCase(downloadParam);

        int days = 7;
        try {
            if (filterPeriod != null && !filterPeriod.isBlank()) {
                days = Integer.parseInt(filterPeriod);
            }
        } catch (Exception ignored) {
            ResponseUtil.sendError(exchange, 400, "Invalid period value");
            return;
        }

        try {
            StringBuilder sql = new StringBuilder("""
                SELECT
                    sl.domain,
                    sl.issuer,
                    sl.days_remaining,
                    sl.expiry_date,
                    sl.public_key_algo,
                    sl.public_key_length,
                    CASE
                        WHEN sl.days_remaining < 0 THEN 'EXPIRED'
                        WHEN sl.days_remaining <= 14 THEN 'EXPIRING SOON'
                        ELSE 'VALID'
                    END AS status
                FROM ssl_logs sl
                WHERE sl.last_checked >= NOW() - (? || ' days')::interval
            """);

            List<Object> params = new ArrayList<>();
            params.add(days);

            if (serviceIdParam != null && !serviceIdParam.isBlank()) {
                sql.append(" AND sl.monitored_service_id = ?");
                params.add(Long.parseLong(serviceIdParam));
            }

            if (statusFilter != null && !statusFilter.isBlank()) {
                sql.append(" AND (CASE " +
                        "WHEN sl.days_remaining < 0 THEN 'EXPIRED' " +
                        "WHEN sl.days_remaining <= 14 THEN 'EXPIRING SOON' " +
                        "ELSE 'VALID' END) = ?");
                params.add(statusFilter.toUpperCase());
            }

            sql.append(" ORDER BY sl.domain ASC");

            List<String> rows = new ArrayList<>();

            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String status = rs.getString("status");
                        rows.add(String.format("""
                            <tr>
                                <td>%s</td>
                                <td class="%s">%s</td>
                                <td class="center">%s</td>
                                <td class="center">%s days</td>
                                <td>%s</td>
                                <td>%s</td>
                                <td>%s bits</td>
                            </tr>
                        """,
                                rs.getString("domain"),
                                status.toLowerCase().replace(" ", "-"),
                                status,
                                rs.getDate("expiry_date") != null ? rs.getDate("expiry_date") : "-",
                                rs.getInt("days_remaining"),
                                rs.getString("issuer") != null ? rs.getString("issuer") : "-",
                                rs.getString("public_key_algo") != null ? rs.getString("public_key_algo") : "-",
                                rs.getInt("public_key_length")
                        ));
                    }
                }
            }

            String html = loadHtmlTemplate()
                    .replace("{{DATE_ISSUED}}",
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    )
                    .replace("{{FILTERS_APPLIED}}",
                            String.format("""
                                <ul style='list-style:none;padding:0;margin:0'>
                                   <li><strong>Service ID:</strong> %s</li>
                                   <li><strong>Period:</strong> %s days</li>
                                   <li><strong>Status:</strong> %s</li>
                                </ul>
                            """,
                                    serviceIdParam != null ? serviceIdParam : "All",
                                    days,
                                    statusFilter != null ? statusFilter.toUpperCase() : "All"
                            )
                    )
                    .replace("{{SSL_ROWS}}",
                            rows.isEmpty()
                                    ? "<tr><td colspan='7' style='text-align:center'>No SSL records found</td></tr>"
                                    : String.join("\n", rows)
                    );

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());

            builder.useUriResolver((baseUri, uri) -> {
                if (uri.startsWith("images/") || uri.startsWith("assets/")) {
                    return Objects.requireNonNull(
                            GenerateSslPdfReports.class.getClassLoader().getResource(uri)
                    ).toString();
                }
                return uri;
            });

            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();

            byte[] pdfBytes = output.toByteArray();

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/pdf");
            exchange.getResponseHeaders().put(
                    Headers.CONTENT_DISPOSITION,
                    (forceDownload ? "attachment" : "inline") + "; filename=\"SSL_Report.pdf\""
            );

            exchange.getResponseSender().send(ByteBuffer.wrap(pdfBytes));

            logger.info("SSL PDF streamed to client (download=" + forceDownload + ")");

        } catch (Exception e) {
            logger.error("Failed to generate SSL PDF", e);
            ResponseUtil.sendError(exchange, 500, "Failed to generate SSL PDF: " + e.getMessage());
        }
    }

    private String loadHtmlTemplate() throws Exception {
        try (var is = getClass().getClassLoader().getResourceAsStream("templates/pdf/ssl-report.html")) {
            if (is == null) throw new RuntimeException("HTML template not found");
            return new String(is.readAllBytes());
        }
    }
}
