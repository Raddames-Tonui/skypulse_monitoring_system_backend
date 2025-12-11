package org.skypulse.tasks;

import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.MultiChannelSender;
import org.skypulse.notifications.email.EmailSender;
import org.skypulse.notifications.telegram.TelegramSender;
import org.skypulse.tasks.tasks.NotificationProcessorTask;
import org.skypulse.tasks.tasks.SslExpiryMonitorTask;
import org.skypulse.tasks.tasks.UptimeCheckTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.skypulse.Main.appScheduler;

public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);


    public static void registerApplicationTasks(boolean dbAvailable, XmlConfiguration cfg) {
        logger.info("[------------ Registering Services ------------]");

        Runnable taskLoader = () -> {
            if (dbAvailable) {
                try {
                    SystemSettings.SystemDefaults defaultSettings = SystemSettings.loadSystemDefaults();
                    List<SystemSettings.ServiceConfig> services = SystemSettings.loadActiveServices();

                    // 1. Register UPTIME CHECK TASKS for each monitored service
                    for (SystemSettings.ServiceConfig service : services) {
                        appScheduler.register(new UptimeCheckTask(service, defaultSettings));
                    }
                    logger.info("[***** Registered {} Services for Uptime check *****]", services.size());

                    // 2. Register NOTIFICATION PROCESSOR TASK
                    MultiChannelSender sender = new MultiChannelSender();
                    sender.addSender("EMAIL", new EmailSender(cfg.notification.email));
                    sender.addSender("TELEGRAM", new TelegramSender(cfg.notification.telegram));

                    int workerThreads = 10;
                    NotificationProcessorTask notificationTask = new NotificationProcessorTask(
                            sender,
                            defaultSettings,
                            workerThreads
                    );
                    appScheduler.register(notificationTask);

                    // 3. Register SSL MONITOR TASK
                    appScheduler.register(new SslExpiryMonitorTask(defaultSettings));

                } catch (Exception e) {
                    logger.error("Failed to register DB-backed tasks", e);
                }
            }
        };

        appScheduler.setTaskLoader(taskLoader);
        taskLoader.run();
        logger.info("[------------ Application tasks registered ------------]");
    }
}
