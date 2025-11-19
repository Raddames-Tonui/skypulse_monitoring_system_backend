package org.skypulse.services;

import org.skypulse.services.ScheduledTask;
import org.skypulse.utils.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Unified TaskScheduler supporting both static tasks and logging to background_tasks.
 */
public class TaskScheduler {
    private static final Logger logger = LoggerFactory.getLogger(TaskScheduler.class);

    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();

    public TaskScheduler() { this(10); } // default pool 10
    public TaskScheduler(int poolSize) {
        this.executor = new ScheduledThreadPoolExecutor(poolSize);
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Register a task for scheduled execution.
     */
    public void register(ScheduledTask task) {
        tasks.add(task);
    }

    /**
     * Start all registered tasks.
     */
    public void start() {
        for (ScheduledTask t : tasks) {
            logger.info("Scheduling task {} every {}s", t.name(), t.intervalSeconds());
            ScheduledFuture<?> f = executor.scheduleAtFixedRate(() -> runTaskWithLogging(t),
                    0, t.intervalSeconds(), TimeUnit.SECONDS);
            futures.add(f);
        }
    }

    private void runTaskWithLogging(ScheduledTask task) {
        long start = System.currentTimeMillis();
        String errorMessage = null;
        try {
            task.execute();
        } catch (Exception e) {
            errorMessage = e.getMessage();
            logger.error("Error in scheduled task {}: {}", task.name(), errorMessage, e);
        } finally {
            long end = System.currentTimeMillis();
            logBackgroundTask(task.name(), start, end, errorMessage);
        }
    }

    /**
     * Logs the execution to background_tasks table.
     */
    private void logBackgroundTask(String taskName, long startMillis, long endMillis, String errorMessage) {
        try (Connection c = JdbcUtils.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO background_tasks(task_name, task_type, status, last_run_at, next_run_at, error_message, date_created, date_modified) " +
                             "VALUES (?, ?, ?, ?, ?, ?, now(), now()) " +
                             "ON CONFLICT (task_name) DO UPDATE SET status = EXCLUDED.status, last_run_at = EXCLUDED.last_run_at, next_run_at = EXCLUDED.next_run_at, error_message = EXCLUDED.error_message, date_modified = now()"
             )) {
            ps.setString(1, taskName);
            ps.setString(2, "SCHEDULED");
            ps.setString(3, errorMessage == null ? "SUCCESS" : "FAILED");
            ps.setTimestamp(4, new Timestamp(startMillis));
            ps.setTimestamp(5, new Timestamp(startMillis + TimeUnit.SECONDS.toMillis(30))); // approximate next run
            ps.setString(6, errorMessage);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[TaskLogger] Failed to log task {}: {}", taskName, e.getMessage(), e);
        }
    }

    /**
     * Stop all tasks and shutdown executor.
     */
    public void stop() {
        logger.info("Shutting down TaskScheduler...");
        for (ScheduledFuture<?> f : futures) f.cancel(true);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("TaskScheduler did not terminate gracefully");
            } else {
                logger.info("TaskScheduler stopped.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("TaskScheduler shutdown interrupted.");
        }
    }
}
