package org.skypulse.tasks;

import org.skypulse.config.database.JdbcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TaskScheduler manages periodic execution of ScheduledTasks.
 * Supports task registration, start, logging, shutdown, and reload.
 * Stores a taskLoader for dynamic reloads without requiring a full app restart.
 */
public class TaskScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TaskScheduler.class);

    private ScheduledThreadPoolExecutor executor;
    private final int poolSize;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();

    private Runnable taskLoader;

    public TaskScheduler() {
        this(10);
    }

    public TaskScheduler(int poolSize) {
        this.poolSize = poolSize;
        createNewExecutor();
    }

    public synchronized void setTaskLoader(Runnable loader) {
        this.taskLoader = loader;
    }

    public synchronized void register(ScheduledTask task) {
        tasks.add(task);
    }

    public synchronized void start() {
        for (ScheduledTask task : tasks) {
            logger.info("Scheduling task {} every {}s", task.name(), task.intervalSeconds());
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                    () -> runTaskWithLogging(task),
                    0,
                    task.intervalSeconds(),
                    TimeUnit.SECONDS
            );
            futures.add(future);
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

    private void logBackgroundTask(String taskName, long startMillis, long endMillis, String errorMessage) {
        try (Connection conn = JdbcUtils.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO background_tasks(task_name, task_type, status, last_run_at, next_run_at, error_message, date_created, date_modified) " +
                             "VALUES (?, ?, ?, ?, ?, ?, now(), now()) " +
                             "ON CONFLICT (task_name) DO UPDATE SET " +
                             "status = EXCLUDED.status, last_run_at = EXCLUDED.last_run_at, " +
                             "next_run_at = EXCLUDED.next_run_at, error_message = EXCLUDED.error_message, date_modified = now()"
             )) {

            ps.setString(1, taskName);
            ps.setString(2, "SCHEDULED");
            ps.setString(3, errorMessage == null ? "SUCCESS" : "FAILED");
            ps.setTimestamp(4, new Timestamp(startMillis));
            ps.setTimestamp(5, new Timestamp(startMillis + TimeUnit.SECONDS.toMillis(30)));
            ps.setString(6, errorMessage);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("[TaskLogger] Failed to log task {}: {}", taskName, e.getMessage(), e);
        }
    }

    public synchronized void shutdown() {
        logger.info("[--------- Shutting down TaskScheduler ---------]");
        for (ScheduledFuture<?> future : futures) future.cancel(true);
        executor.shutdownNow();
        futures.clear();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("TaskScheduler did not terminate gracefully");
            } else {
                logger.info("[--------- TaskScheduler stopped ---------]");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("TaskScheduler shutdown interrupted.");
        }
    }

    private void createNewExecutor() {
        this.executor = new ScheduledThreadPoolExecutor(poolSize);
        this.executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void reload() {
        logger.info("[--------- Reloading TaskScheduler ---------]");

        shutdown();
        createNewExecutor();

        tasks.clear();
        futures.clear();

        if (taskLoader != null) {
            taskLoader.run();
        }

        start();
        logger.info("[--------- TaskScheduler reload complete ---------]");
    }
}
