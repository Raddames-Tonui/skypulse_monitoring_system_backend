package org.skypulse.handlers.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.skypulse.notifications.TemplateLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class TemplatePreviewHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(TemplatePreviewHandler.class);
    private final TemplateLoader loader = new TemplateLoader();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        Map<String, String> queryParams = exchange.getQueryParameters().entrySet().stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().getFirst()), HashMap::putAll);

        String eventType = queryParams.get("event_type");
        String channel = queryParams.getOrDefault("channel", "EMAIL").toUpperCase();

        if (eventType == null || eventType.isBlank()) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("Missing event_type");
            return;
        }

        // Fetch template info from DB by event_type
        String dbTemplate = null;
        String templateKey = null;
        Map<String, Object> sampleData = new HashMap<>();

        try (Connection conn = org.skypulse.config.database.JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT body_template, body_template_key, sample_data " +
                             "FROM notification_templates WHERE event_type = ? LIMIT 1"
             )) {
            ps.setString(1, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    dbTemplate = rs.getString("body_template");
                    templateKey = rs.getString("body_template_key");
                    String sampleJson = rs.getString("sample_data");
                    if (sampleJson != null && !sampleJson.isBlank()) {
                        sampleData = mapper.readValue(sampleJson, Map.class);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load template from DB for event_type={}", eventType, e);
        }

        // Load the template using TemplateLoader
        String template = loader.load(channel, dbTemplate, templateKey);

        if (template == null || template.isBlank()) {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("Template not found for event_type=" + eventType + " channel=" + channel);
            return;
        }

        // Render Mustache template with sample data
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "preview");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, sampleData).flush();

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(writer.toString(), StandardCharsets.UTF_8);

        logger.info("Template preview rendered for event_type={} channel={}", eventType, channel);
    }
}
