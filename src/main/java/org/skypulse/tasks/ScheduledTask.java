package org.skypulse.tasks;

public interface ScheduledTask {

    String name();

    long intervalSeconds();

    void execute();
}
