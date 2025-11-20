package org.skypulse.services;

import org.skypulse.config.database.dtos.SystemSettings;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.MultiChannelSender;
import org.skypulse.notifications.email.EmailSender;
import org.skypulse.services.tasks.*;
import org.skypulse.config.database.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.List;

import static org.skypulse.Main.appScheduler;

/**
 * Register all Services (system + monitoring)
 */
public class ApplicationTasks {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTasks.class);

    public static void registerApplicationTasks(boolean dbAvailable, XmlConfiguration cfg) {
        logger.info("[------------ Registering Services ------------]");

        if (dbAvailable) {
            try (Connection conn = JdbcUtils.getConnection()) {
                SystemSettings.SystemDefaults defaults = SystemSettings.loadSystemDefaults(conn);
                List<SystemSettings.ServiceConfig> services = SystemSettings.loadActiveServices(conn);

                for (SystemSettings.ServiceConfig service : services) {
                    appScheduler.register(new UptimeCheckTask(service, defaults));
                }

                logger.info("Registered {} Services for Up time check", services.size());

            } catch (Exception e) {
                logger.error("Failed to register UptimeCheckTasks", e);
            }
        }

        // System / maintenance tasks (DB-independent)
        // appScheduler.register(new DiskHealthCheckTask("/"));
        // appScheduler.register(new LogRetentionCleanupTask());

        if (dbAvailable) {
            activateDbBackedTasks(cfg);
        }

        logger.info("[------------ Application tasks registered ------------]");
    }

    /**
     * DB-backed tasks activated only when DB is online.
     */
    public static void activateDbBackedTasks(XmlConfiguration cfg) {

        logger.info("[--------- Starting DB-backed tasks -------------]");
//        appScheduler.register(new EventQueueProcessorTask());
//         appScheduler.register(new NotificationDispatchTask());


        appScheduler.register(new SslExpiryMonitorTask());


        MultiChannelSender sender = new MultiChannelSender();
        sender.addSender("EMAIL", new EmailSender(cfg.notification.email));
         // sender.addSender("TELEGRAM", new TelegramSender(cfg.notification.telegram));

        appScheduler.register(new NotificationProcessorTask(sender));

        logger.info("[--------- DB-backed tasks Started -------------]");
    }
}
