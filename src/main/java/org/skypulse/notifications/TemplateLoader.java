package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * TemplateLoader
 *
 * Applies notificationChannel-based logic:
 *
 * EMAIL:
 *   1. DB body_template
 *   2. Classpath fallback (body_template_key)
 *
 * OTHER CHANNELS:
 *   - DB body_template ONLY (NO classpath fallback)
 *
 */
public class TemplateLoader {

    private static final Logger logger = LoggerFactory.getLogger(TemplateLoader.class);

    public TemplateLoader() {
        logger.info("[TemplateLoader] Initialized (notificationChannel-aware, no filesystem)");
    }

    public String load(String notificationChannel, String bodyTemplate, String bodyTemplatePath) {

        if (notificationChannel == null || notificationChannel.isBlank()) {
            logger.error("[TemplateLoader] Missing notificationChannel");
            return null;
        }

        notificationChannel = notificationChannel.trim().toUpperCase();

        switch (notificationChannel) {

            case "EMAIL":
                return loadEmail(bodyTemplate, bodyTemplatePath);

            case "SMS":
            case "TELEGRAM":
                return loadNonEmailChannel(notificationChannel, bodyTemplate);

            default:
                logger.warn("[TemplateLoader] Unknown notificationChannel={} using DB only", notificationChannel);
                return bodyTemplate;
        }
    }


    private String loadEmail(String bodyTemplate, String key) {

        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            logger.info("[TemplateLoader] EMAIL using DB template");
            return bodyTemplate;
        }

        if (key == null || key.isBlank()) {
            logger.error("[TemplateLoader] EMAIL fallback key missing");
            return null;
        }

        String cp = loadFromClasspath(key);
        if (cp != null) return cp;

        logger.error("[TemplateLoader] EMAIL has no DB or classpath template available");
        return null;
    }


    private String loadNonEmailChannel(String notificationChannel, String bodyTemplate) {

        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            logger.info("[TemplateLoader] {} using DB template", notificationChannel);
            return bodyTemplate;
        }

        logger.error("[TemplateLoader] {} has NO DB template", notificationChannel);
        return null;
    }


    private String loadFromClasspath(String key) {
        String path = "templates/" + key;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                logger.info("[TemplateLoader] Loaded classpath template key={} notificationChannel={}", key, "EMAIL");
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.error("[TemplateLoader] Failed loading classpath template {} notificationChannel={} - {}",
                    key, "EMAIL", e.getMessage());
        }

        return null;
    }
}
