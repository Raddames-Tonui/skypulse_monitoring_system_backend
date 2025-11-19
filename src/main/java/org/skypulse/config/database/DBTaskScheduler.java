package org.skypulse.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Task scheduler for background database reconnection.
 */
public class DBTaskScheduler {
    private static final Logger logger = LoggerFactory.getLogger(DBTaskScheduler.class);
    private static final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    public static void scheduleReconnect(Runnable task) {
        executor.scheduleAtFixedRate(task, 10, 20, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        try {
            executor.shutdownNow();
            logger.info("TaskScheduler stopped.");
        } catch (Exception e) {
            logger.warn("Error shutting down TaskScheduler: {}", e.getMessage());
        }
    }
}
