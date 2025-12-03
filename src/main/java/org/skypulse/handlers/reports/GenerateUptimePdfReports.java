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

public class GenerateUptimePdfReports implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GenerateUptimePdfReports.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) {

        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String filterPeriod = DatabaseUtils.getParam(exchange.getQueryParameters(), "period");
        if (filterPeriod == null || filterPeriod.isBlank()) filterPeriod = "7";

        int days;
        try {
            days = Integer.parseInt(filterPeriod);
        } catch (NumberFormatException e) {
            ResponseUtil.sendError(exchange, 400, "Invalid period value: must be an integer");
            return;
        }

        String serviceIdParam = DatabaseUtils.getParam(exchange.getQueryParameters(), "service_id");
        Long serviceId = (serviceIdParam != null && !serviceIdParam.isBlank())
                ? Long.parseLong(serviceIdParam)
                : null;

        String statusFilter = DatabaseUtils.getParam(exchange.getQueryParameters(), "status");

        String downloadParam = DatabaseUtils.getParam(exchange.getQueryParameters(), "download");
        boolean forceDownload =
                "1".equals(downloadParam) ||
                        "true".equalsIgnoreCase(downloadParam) ||
                        "yes".equalsIgnoreCase(downloadParam);

        try {
            List<String> rows = new ArrayList<>();

            StringBuilder sqlBuilder = new StringBuilder("""
                SELECT 
                    ms.monitored_service_name,
                    ul.status,
                    ul.response_time_ms,
                    ul.http_status,
                    ul.error_message,
                    ul.checked_at
                FROM uptime_logs ul
                INNER JOIN monitored_services ms 
                       ON ms.monitored_service_id = ul.monitored_service_id
                WHERE ul.checked_at >= NOW() - (? || ' days')::interval
            """);

            if (serviceId != null) sqlBuilder.append(" AND ms.monitored_service_id = ?");
            if (statusFilter != null && !statusFilter.isBlank()) sqlBuilder.append(" AND ul.status = ?");

            sqlBuilder.append(" ORDER BY ul.checked_at DESC"); // newest first

            String sql = sqlBuilder.toString();

            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                int index = 1;
                ps.setInt(index++, days);

                if (serviceId != null) ps.setLong(index++, serviceId);
                if (statusFilter != null && !statusFilter.isBlank())
                    ps.setString(index, statusFilter.toUpperCase());

                DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                int counter = 1;

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {

                        String status = rs.getString("status");

                        String row = String.format("""
                            <tr>
                              <td class="center">%d</td>
                              <td>%s</td>
                              <td class="%s">%s</td>
                              <td class="center">%s</td>
                              <td class="center">%s</td>
                              <td>%s</td>
                              <td>%s</td>
                            </tr>
                        """,
                                counter++,
                                rs.getString("monitored_service_name"),
                                "UP".equalsIgnoreCase(status) ? "up" : "down",
                                status != null ? status : "-",
                                rs.getObject("response_time_ms") != null ? rs.getInt("response_time_ms") : "-",
                                rs.getObject("http_status") != null ? rs.getInt("http_status") : "-",
                                rs.getTimestamp("checked_at") != null
                                        ? rs.getTimestamp("checked_at").toLocalDateTime().format(df)
                                        : "-",
                                rs.getString("error_message") != null ? rs.getString("error_message") : "-"
                        );

                        rows.add(row);
                    }
                }
            }

            StringBuilder filtersHtml = new StringBuilder("<ul style='list-style: none; padding: 0;'>");

            filtersHtml.append("<li><strong>Period:</strong> ").append(days).append(" day(s)</li>");
            filtersHtml.append("<li><strong>Service ID:</strong> ").append(
                    serviceId != null ? serviceId : "All"
            ).append("</li>");
            filtersHtml.append("<li><strong>Status:</strong> ").append(
                    statusFilter != null && !statusFilter.isBlank()
                            ? statusFilter.toUpperCase()
                            : "All"
            ).append("</li>");

            filtersHtml.append("</ul>");

            String html = loadHtmlTemplate()
                    .replace("{{DATE_ISSUED}}",
                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    )
                    .replace("{{UPTIME_ROWS}}",
                            rows.isEmpty()
                                    ? "<tr><td colspan='7' style='text-align:center'>No records found</td></tr>"
                                    : String.join("\n", rows)
                    )
                    .replace("{{FILTERS_APPLIED}}", filtersHtml.toString());

            ByteArrayOutputStream pdfStream = new ByteArrayOutputStream();

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());

            builder.useUriResolver((baseUri, uri) -> {
                if (uri.startsWith("assets/") || uri.startsWith("images/") || uri.startsWith("logos/")) {
                    return GenerateUptimePdfReports.class
                            .getClassLoader()
                            .getResource(uri)
                            .toString();
                }
                return uri;
            });

            builder.withHtmlContent(html, null);
            builder.toStream(pdfStream);
            builder.run();

            byte[] pdfBytes = pdfStream.toByteArray();

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/pdf");

            if (forceDownload) {
                exchange.getResponseHeaders().put(
                        Headers.CONTENT_DISPOSITION,
                        "attachment; filename=\"Uptime_Report.pdf\""
                );
            } else {
                exchange.getResponseHeaders().put(
                        Headers.CONTENT_DISPOSITION,
                        "inline; filename=\"Uptime_Report.pdf\""
                );
            }

            exchange.getResponseSender().send(ByteBuffer.wrap(pdfBytes));
            logger.info("Uptime PDF streamed to client (download=" + forceDownload + ")");

        } catch (Exception e) {
            logger.error("Failed to generate Uptime PDF", e);
            ResponseUtil.sendError(exchange, 500, "Failed to generate uptime PDF: " + e.getMessage());
        }
    }

    private String loadHtmlTemplate() throws Exception {
        try (var is = getClass().getClassLoader()
                .getResourceAsStream("templates/pdf/uptime-report.html")) {

            if (is == null) throw new RuntimeException("HTML template not found");
            return new String(is.readAllBytes());
        }
    }
}
