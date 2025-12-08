package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TemplateLoader
 * Loads templates according to storageMode:

 * STORAGE_MODE PRIORITY/BEHAVIOR
 * DATABASE     Only DB template. Returns null if not found.
 * FILESYSTEM   Only filesystem template (if enabled). Returns null if not found or disabled.
 * HYBRID       DB -> Filesystem -> Classpath fallback.
 */
public class TemplateLoader {

    private static final Logger logger = LoggerFactory.getLogger(TemplateLoader.class);

    private final String externalBasePath;
    private final boolean filesystemEnabled;

    public TemplateLoader() {
        this.externalBasePath = System.getProperty("notification.templates.path");
        this.filesystemEnabled = (externalBasePath != null);

        if (filesystemEnabled) {
            logger.info("[TemplateLoader] Filesystem templates ENABLED at path={}", externalBasePath);
        } else {
            logger.info("[TemplateLoader] Filesystem templates DISABLED. Using classpath only.");
        }
    }

    public String load(String storageMode, String dbTemplate, String key, String channel) {
        if (key == null || key.isBlank()) {
            logger.error("[TemplateLoader] Template key missing for channel={}", channel);
            return null;
        }

        storageMode = storageMode != null ? storageMode.toUpperCase() : "HYBRID";

        switch (storageMode) {
            case "DATABASE":
                if (dbTemplate != null && !dbTemplate.isBlank()) {
                    logger.info("[TemplateLoader] Using DB template for key={} channel={}", key, channel);
                    return dbTemplate;
                }
                logger.warn("[TemplateLoader] DB template not found for key={} channel={}", key, channel);
                return null;

            case "FILESYSTEM":
                if (filesystemEnabled) {
                    String fsTpl = loadFromFilesystem(key, channel);
                    if (fsTpl != null) return fsTpl;
                    logger.warn("[TemplateLoader] Filesystem template not found for key={} channel={}", key, channel);
                    return null;
                } else {
                    logger.warn("[TemplateLoader] Filesystem templates disabled for key={} channel={}", key, channel);
                    return null;
                }

            case "HYBRID":
                if (dbTemplate != null && !dbTemplate.isBlank()) {
                    logger.info("[TemplateLoader] [HYBRID] Using DB template for key={} channel={}", key, channel);
                    return dbTemplate;
                }

                if (filesystemEnabled) {
                    String fsTpl = loadFromFilesystem(key, channel);
                    if (fsTpl != null) {
                        logger.info("[TemplateLoader] [HYBRID] Using Filesystem template for key={} channel={}", key, channel);
                        return fsTpl;
                    }
                }

                String cpTpl = loadFromClasspath(key, channel);
                if (cpTpl != null) {
                    logger.info("[TemplateLoader] [HYBRID] Using Classpath template for key={} channel={}", key, channel);
                    return cpTpl;
                }

                logger.error("[TemplateLoader] [HYBRID] No template found for key={} channel={}", key, channel);
                return null;

            default:
                logger.warn("[TemplateLoader] Unknown storageMode={} for key={} channel={}", storageMode, key, channel);
                return null;
        }
    }

    private String loadFromClasspath(String key, String channel) {
        String path = "templates/" + key;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.error("[TemplateLoader] Failed to load classpath template {} for channel={} - {}", key, channel, e.getMessage());
        }
        return null;
    }

    private String loadFromFilesystem(String key, String channel) {
        try {
            String fullPath = Paths.get(externalBasePath, key).toString();
            return Files.readString(Paths.get(fullPath));
        } catch (Exception e) {
            logger.error("[TemplateLoader] Filesystem template missing {} for channel={} - {}", key, channel, e.getMessage());
            return null;
        }
    }
}
