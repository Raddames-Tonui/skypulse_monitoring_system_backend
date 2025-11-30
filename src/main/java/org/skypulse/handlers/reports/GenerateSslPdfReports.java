package org.skypulse.handlers.reports;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.skypulse.config.database.JdbcUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class GenerateSslPdfReports implements HttpHandler {

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        int days = 30;
        Deque<String> queryDays = exchange.getQueryParameters().get("days");
        if (queryDays != null && !queryDays.isEmpty()) {
            try { days = Integer.parseInt(queryDays.getFirst()); } catch (Exception ignored) {}
        }

        List<Map<String, Object>> sslLogs = new ArrayList<>();
        Map<String, Integer> sslExpirySummary = new LinkedHashMap<>();

        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     """
                            
                         """
             )) {

            ps.setInt(1, days);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("service", rs.getString("service_name"));
                row.put("domain", rs.getString("domain"));
                row.put("issuer", rs.getString("issuer"));
                row.put("expiry_date", rs.getDate("expiry_date"));
                row.put("days_remaining", rs.getInt("days_remaining"));
                row.put("chain_valid", rs.getBoolean("chain_valid"));
                sslLogs.add(row);

                sslExpirySummary.put(rs.getString("domain"), rs.getInt("days_remaining"));
            }
        }

        StringBuilder tableRows = new StringBuilder();
        for (Map<String, Object> row : sslLogs) {
            String cls = (int) row.get("days_remaining") <= 0 ? "expired" :
                    (int) row.get("days_remaining") <= 7 ? "expiring" : "valid";
            tableRows.append("<tr>")
                    .append("<td>").append(row.get("service")).append("</td>")
                    .append("<td>").append(row.get("domain")).append("</td>")
                    .append("<td>").append(row.get("issuer")).append("</td>")
                    .append("<td>").append(row.get("expiry_date")).append("</td>")
                    .append("<td>").append(row.get("days_remaining")).append("</td>")
                    .append("<td class='").append(cls).append("'>").append(row.get("chain_valid")).append("</td>")
                    .append("</tr>");
        }

        StringBuilder svg = new StringBuilder();
        int x = 50, width = 40, maxHeight = 200;
        for (String domain : sslExpirySummary.keySet()) {
            int daysRemaining = sslExpirySummary.get(domain);
            int barHeight = (int) ((double) (days - daysRemaining) / days * maxHeight);
            int y = maxHeight - barHeight;
            svg.append(String.format("<rect x='%d' y='%d' width='%d' height='%d' class='bar'></rect>", x, y, width, barHeight));
            svg.append(String.format("<text x='%d' y='%d' class='label'>%s</text>", x + width / 2, maxHeight + 15, domain));
            x += width + 30;
        }

        String html = loadTemplate()
                .replace("{{REPORT_PERIOD}}", "Next " + days + " days")
                .replace("{{GENERATED_ON}}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .replace("{{SSL_ROWS}}", tableRows.toString())
                .replace("{{SSL_EXPIRY_CHART_SVG}}", svg.toString());

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/pdf");
        exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "attachment; filename=ssl_report.pdf");
        try (OutputStream os = exchange.getOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.useDefaultPageSize(8.27f, 11.69f, PdfRendererBuilder.PageSizeUnits.INCHES);
            builder.run();
        }
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/pdf/ssl-report.html")) {
            if (is == null) throw new FileNotFoundException("Template not found: " + "/templates/pdf/ssl-report.html");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
