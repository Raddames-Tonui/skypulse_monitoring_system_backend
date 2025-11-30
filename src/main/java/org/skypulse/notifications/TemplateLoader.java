package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TemplateLoader {

    private static final Logger logger = LoggerFactory.getLogger(TemplateLoader.class);

    private final String externalBasePath;

    public TemplateLoader() {
        this.externalBasePath = System.getProperty(
                "notification.templates.path",
                System.getProperty("user.dir") + "/templates"
        );
    }

    public String load(
            String storageMode,
            String dbTemplate,
            String templateKey
    ) {
        if (storageMode == null) {
            storageMode = "hybrid";
        }

        switch (storageMode.toLowerCase()) {

            case "database":
                return fromDatabase(dbTemplate, templateKey);

            case "filesystem":
                return fromFilesystem(templateKey);

            case "hybrid":
            default:
                return fromHybrid(dbTemplate, templateKey);
        }
    }

    // STORAGE MODE: database
    private String fromDatabase(String dbTemplate, String key) {
        if (dbTemplate == null || dbTemplate.isBlank()) {
            logger.error("[ TemplateLoader ] DB template is empty for key={}", key);
        }
        return dbTemplate;
    }

    // STORAGE MODE: filesystem
    private String fromFilesystem(String key) {
        if (key == null || key.isBlank()) return null;

        try {
            return Files.readString(Paths.get(externalBasePath, key));
        } catch (Exception e) {
            logger.error("[ TemplateLoader ] FS template missing: {} - {}", key, e.getMessage());
            return null;
        }
    }

    // STORAGE MODE: hybrid
    private String fromHybrid(String dbTemplate, String key) {

        // 1 External FS
        if (key != null) {
            try {
                String tpl = Files.readString(Paths.get(externalBasePath, key));
                logger.info("[ TemplateLoader ] Loaded from filesystem: {}", key);
                return tpl;
            } catch (Exception ignore) {}
        }

        // 2 Classpath
        if (key != null) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("templates/" + key)) {

                if (in != null) {
                    String tpl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    logger.info("[ TemplateLoader ] Loaded from classpath: {}", key);
                    return tpl;
                }
            } catch (Exception ignore) {}
        }

        // 3 DB fallback
        if (dbTemplate != null && !dbTemplate.isBlank()) {
            logger.info("[ TemplateLoader ] Using DB fallback for key={}", key);
            return dbTemplate;
        }

        logger.error("[ TemplateLoader ] No template found anywhere (key={})", key);
        return null;
    }
}
