package org.skypulse.services;

import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.MultiChannelSender;
import org.skypulse.notifications.email.EmailSender;
import org.skypulse.services.tasks.NotificationProcessorTask;
import org.skypulse.services.tasks.SslExpiryMonitorTask;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.services.tasks.UptimeCheckTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.List;

import static org.skypulse.Main.appScheduler;

public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);

    public static void registerApplicationTasks(boolean dbAvailable, XmlConfiguration cfg) {
        logger.info("[------------ Registering Services ------------]");

        Runnable taskLoader = () -> {
            if (dbAvailable) {
                try (Connection conn = JdbcUtils.getConnection()) {
                    SystemSettings.SystemDefaults defaultSettings = SystemSettings.loadSystemDefaults(conn);
                    List<SystemSettings.ServiceConfig> services = SystemSettings.loadActiveServices(conn);

                    // 1. Register UPTIME CHECK TASKS for each monitored service
                    for (SystemSettings.ServiceConfig service : services) {
                        appScheduler.register(new UptimeCheckTask(service, defaultSettings));
                    }

                    logger.info("[***** Registered {} Services for Up time check *****]", services.size());

                    // 2. Register NOTIFICATION PROCESSOR TASK with system default Settings
                    MultiChannelSender sender = new MultiChannelSender();
                    sender.addSender("EMAIL", new EmailSender(cfg.notification.email));
                    // sender.addSender("TELEGRAM", new TelegramSender(cfg.notification.telegram));

                    appScheduler.register(new NotificationProcessorTask(sender, defaultSettings));

                    // 3. Register SSL MONITOR TASK
                    appScheduler.register(new SslExpiryMonitorTask(defaultSettings));


                } catch (Exception e) {
                    logger.error("Failed to register DB-backed tasks", e);
                }
            }
        };

        appScheduler.setTaskLoader(taskLoader);
        taskLoader.run();
        appScheduler.start();

        logger.info("[------------ Application tasks registered ------------]");
    }
}
