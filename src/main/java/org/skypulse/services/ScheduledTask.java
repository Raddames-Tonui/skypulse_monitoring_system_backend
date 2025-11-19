package org.skypulse.services;

public interface ScheduledTask {
    /**
     * A short name used for logging and registry.
     */
    String name();

    /**
     * Interval in seconds between executions.
     */
    long intervalSeconds();

    /**
     * The work to do. Implementations should catch exceptions and log them.
     */
    void execute();
}
