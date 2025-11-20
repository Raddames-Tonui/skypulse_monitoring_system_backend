package org.skypulse.config.utils;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Centralized MDC context management for consistent logging.
 * Adds "component" and "trace.id" metadata to every log entry.
 */
public class LogContext {
    private LogContext() {};

    public static void start(String component) {
        MDC.put("component", component);
        MDC.put("trace.id", UUID.randomUUID().toString());
    }

    public static void start(String component, String traceId) {
        MDC.put("component", component);
        MDC.put("trace.id", traceId != null ? traceId : UUID.randomUUID().toString());
    }

    public static void clear() {
        MDC.clear();
    }

    public static String getTraceId() {
        return MDC.get("trace.id");
    }
}



/**
 *| Action                              | Rule                                       |
 * | ----------------------------------- | ------------------------------------------ |
 * | At start of any operation           | `LogContext.start("ComponentName")`        |
 * | At end (in finally block)           | `LogContext.clear()`                       |
 * | Never log outside context           | Each thread must have its own MDC          |
 * | Include in async jobs               | Call `LogContext.start()` inside runnables |
 * | Always test with `trace.id` visible | Ensures correlation across logs            |
 * */
