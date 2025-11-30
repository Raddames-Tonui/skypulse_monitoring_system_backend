package org.skypulse.services;

import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.MultiChannelSender;
import org.skypulse.notifications.email.EmailSender;
import org.skypulse.services.tasks.NotificationProcessorTask;
import org.skypulse.services.tasks.SslExpiryMonitorTask;
import org.skypulse.services.tasks.UptimeCheckTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.skypulse.Main.appScheduler;

public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);
    private static final AtomicInteger INIT_COUNT = new AtomicInteger(0);

    public void init() {
        int count = INIT_COUNT.incrementAndGet();
        logger.warn("ApplicationTasks.init() called {} times", count);
    }

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
                    // sender.addSender("TELEGRAM", new TelegramSender(cfg.notification.telegram));

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
