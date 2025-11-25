package org.skypulse.config.database.dtos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SystemSettingsCache {

    private static final Logger logger = LoggerFactory.getLogger(SystemSettingsCache.class);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static volatile SystemSettings.SystemDefaults systemDefaults;
    private static volatile List<SystemSettings.ServiceConfig> services;

    private SystemSettingsCache() {}

    public static void init(long refreshIntervalMinutes) {
        loadFromDatabase(); // load once immediately

        // Schedule periodic refresh
        scheduler.scheduleAtFixedRate(SystemSettingsCache::loadFromDatabase,
                refreshIntervalMinutes,
                refreshIntervalMinutes,
                TimeUnit.MINUTES);
    }

    private static void loadFromDatabase() {
        try {
            Map<String, Object> all = SystemSettings.loadAll();
            systemDefaults = (SystemSettings.SystemDefaults) all.get("systemDefaults");
            services = (List<SystemSettings.ServiceConfig>) all.get("services");
            logger.info("System settings and active services refreshed successfully.");
        } catch (Exception e) {
            logger.error("Failed to refresh system settings from DB", e);
        }
    }

    public static SystemSettings.SystemDefaults getSystemDefaults() {
        return systemDefaults;
    }

    public static List<SystemSettings.ServiceConfig> getServices() {
        return services;
    }
}
