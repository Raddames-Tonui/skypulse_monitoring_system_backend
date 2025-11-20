package org.skypulse.services;

public interface ScheduledTask {

    String name();

    long intervalSeconds();

    void execute();
}
