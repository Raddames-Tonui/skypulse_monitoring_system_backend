package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

public class TemplateLoader {

    private static final Logger logger = LoggerFactory.getLogger(TemplateLoader.class);

    private final String externalBasePath;

    public TemplateLoader() {
        this.externalBasePath = System.getProperty(
                "notification.templates.path",
                System.getProperty("user.dir") + "/templates"
        );
    }

    /**
     * Load template according to storage mode and channel.
     * storageMode "database", "filesystem", or "hybrid"
     * dbTemplate  DB template (can be null)
     * templateKey Key for filesystem template
     * channel     Channel code: EMAIL, SMS, TELEGRAM, etc.
     */
    public String load(String storageMode, String dbTemplate, String templateKey, String channel) {

        if (storageMode == null) storageMode = "hybrid";

        return switch (storageMode.toLowerCase()) {
            case "database" -> fromDatabase(dbTemplate, templateKey, channel);
            case "filesystem" -> fromFilesystem(templateKey, channel);
            default -> fromHybrid(dbTemplate, templateKey, channel);
        };
    }

    private String fromDatabase(String dbTemplate, String key, String channel) {
        if (dbTemplate == null || dbTemplate.isBlank()) {
            logger.warn("[TemplateLoader] DB template empty for key={} channel={}", key, channel);
        } else {
            logger.info("[TemplateLoader] Using DB template for key={} channel={}", key, channel);
        }
        return dbTemplate;
    }

    private String fromFilesystem(String key, String channel) {
        if (key == null || key.isBlank()) return null;

        try {
            String tpl = Files.readString(Paths.get(externalBasePath, key));
            logger.info("[TemplateLoader] Loaded from filesystem: {} for channel={}", key, channel);
            return tpl;
        } catch (Exception e) {
            logger.error("[TemplateLoader] FS template missing: {} for channel={} - {}", key, channel, e.getMessage());
            return null;
        }
    }

    private String fromHybrid(String dbTemplate, String key, String channel) {
        String tpl = null;

        // SMS/Telegram always DB
        if (!Objects.equals(channel, "EMAIL")) {
            if (dbTemplate != null && !dbTemplate.isBlank()) {
                logger.info("[TemplateLoader] Using DB template for channel={}", channel);
                return dbTemplate;
            } else {
                logger.error("[TemplateLoader] No DB template for non-email channel={}", channel);
                return null;
            }
        }

        // EMAIL: DB fallback first
        if (dbTemplate != null && !dbTemplate.isBlank()) {
            logger.info("[TemplateLoader] Using DB template for EMAIL channel");
            return dbTemplate;
        }

        // Filesystem template
        if (key != null) {
            try {
                tpl = Files.readString(Paths.get(externalBasePath, key));
                logger.info("[TemplateLoader] Loaded from filesystem: {} for EMAIL", key);
                return tpl;
            } catch (Exception ignore) {}
        }

        // Classpath template
        if (key != null) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("templates/" + key)) {
                if (in != null) {
                    tpl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    logger.info("[TemplateLoader] Loaded from classpath: {} for EMAIL", key);
                    return tpl;
                }
            } catch (Exception ignore) {}
        }


        return tpl;
    }
}
