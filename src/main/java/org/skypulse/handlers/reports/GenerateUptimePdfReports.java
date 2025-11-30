package org.skypulse.handlers.reports;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
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

        try {
            List<String> rows = new ArrayList<>();

            String sql = """
                SELECT ms.monitored_service_name, ul.status, ul.response_time_ms, ul.http_status, ul.error_message, ul.checked_at
                FROM monitored_services ms
                LEFT JOIN uptime_logs ul ON ms.monitored_service_id = ul.monitored_service_id
                WHERE ul.checked_at >= NOW() - (? || ' days')::interval
                ORDER BY ul.checked_at ASC
            """;

            try (Connection conn = JdbcUtils.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setInt(1, days);
                try (ResultSet rs = ps.executeQuery()) {
                    DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    while (rs.next()) {
                        String status = rs.getString("status");
                        String row = String.format("""
                            <tr>
                              <td>%s</td>
                              <td class="%s">%s</td>
                              <td class="center">%s</td>
                              <td class="center">%s</td>
                              <td>%s</td>
                            </tr>
                        """,
                                rs.getTimestamp("checked_at") != null
                                        ? rs.getTimestamp("checked_at").toLocalDateTime().format(df)
                                        : "-",
                                "UP".equalsIgnoreCase(status) ? "up" : "down",
                                status != null ? status : "-",
                                rs.getObject("response_time_ms") != null ? rs.getInt("response_time_ms") : "-",
                                rs.getObject("http_status") != null ? rs.getInt("http_status") : "-",
                                rs.getString("error_message") != null ? rs.getString("error_message") : "-"
                        );
                        rows.add(row);
                    }
                }
            }

            String html = loadHtmlTemplate()
                    .replace("{{DATE_ISSUED}}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .replace("{{UPTIME_ROWS}}", rows.isEmpty() ? "<tr><td colspan='5'>No records found</td></tr>" : String.join("\n", rows));

            // Ensure output directory exists
            File outputFile = new File("src/out/Uptime_Report.pdf");
            File parentDir = outputFile.getParentFile();
            if (!parentDir.exists()) parentDir.mkdirs();

            try (FileOutputStream os = new FileOutputStream(outputFile)) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.withHtmlContent(html, null);
                builder.toStream(os);
                builder.run();
            }

            logger.info("Uptime PDF successfully generated at {}", outputFile.getAbsolutePath());
            ResponseUtil.sendSuccess(exchange, "Uptime PDF generated successfully", Map.of("file", outputFile.getAbsolutePath()));

        } catch (Exception e) {
            logger.error("Failed to generate Uptime PDF", e);
            ResponseUtil.sendError(exchange, 500, "Failed to generate uptime PDF: " + e.getMessage());
        }
    }

    private String loadHtmlTemplate() throws Exception {
        try (var is = getClass().getClassLoader().getResourceAsStream("templates/pdf/uptime-report.html")) {
            if (is == null) throw new RuntimeException("HTML template not found");
            return new String(is.readAllBytes());
        }
    }
}
