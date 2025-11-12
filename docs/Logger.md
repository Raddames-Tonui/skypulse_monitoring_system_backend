## 🚫 Why You Should Never Use `System.err` or `System.out`

| Issue                                  | Explanation                                                                    |
| -------------------------------------- | ------------------------------------------------------------------------------ |
| ❌ **No context**                       | Raw prints don’t include timestamps, thread info, or class origin.             |
| ❌ **Hard to manage**                   | They can’t be filtered, rotated, or written to log files automatically.        |
| ❌ **Performance issues**               | Console writes are blocking; under load they can slow servers.                 |
| ❌ **No log levels**                    | You can’t differentiate between INFO, WARN, or ERROR — everything just prints. |
| ❌ **Unstructured output**              | Not compatible with log collectors (e.g., ELK, Loki, Grafana, Datadog).        |
| ✅ **Proper logging (SLF4J + Logback)** | Gives structured output, async logging, and configurable verbosity.            |

> **Use Logback for every kind of output that should be recorded.**
> `System.out.println()` should only be used for *one-time developer testing*.

---

## 🧭 The 5 Logger Levels and When to Use Each

| Level            | Purpose                                  | Typical Use                                                                | Production Behavior       |
| ---------------- | ---------------------------------------- | -------------------------------------------------------------------------- | ------------------------- |
| **TRACE**        | Most granular diagnostic detail          | To trace flow through individual methods (e.g., algorithm steps)           | 🔕 Disabled (too verbose) |
| **DEBUG**        | Developer-oriented detail                | Variable states, config loading info, connection setup                     | 🔕 Usually disabled       |
| **INFO**         | Normal, high-level operational messages  | “Server started on port 8080”, “User logged in”, “Key loaded successfully” | ✅ Enabled                 |
| **WARN**         | Something unexpected but recoverable     | “Retrying DB connection”, “.env missing, using defaults”                   | ✅ Enabled                 |
| **ERROR**        | Failures that affect the system or user  | Exceptions, initialization failures, data corruption                       | ✅ Enabled                 |
| *(Bonus: FATAL)* | Rarely used in SLF4J; handled as `ERROR` | Application cannot continue                                                | ✅ Enabled                 |

---

### 🔍 Example: Proper Logging Usage

```java
private static final Logger logger = LoggerFactory.getLogger(MyService.class);

public void connectToDatabase() {
    logger.info("Initializing database connection...");
    try {
        // connection logic
        logger.debug("Database connection string: {}", dbUrl);
    } catch (SQLException e) {
        logger.error("Database connection failed", e);
    }
}
```

**Behavior:**

* In dev mode: shows all `DEBUG`, `INFO`, `WARN`, `ERROR` logs.
* In prod mode: shows only `INFO`, `WARN`, `ERROR`.
* Async logging ensures no slowdowns.

---

## ⚙️ What Happens Internally

When you call `logger.info("Server started")`, Logback:

1. Checks the current log level (from XML config).
2. Formats message with timestamp, thread, and class.
3. Routes it to console/file/error logs automatically.
4. Handles rotation, queueing, and async dispatch.

---

## 🧠 Level Hierarchy

```
TRACE < DEBUG < INFO < WARN < ERROR
```

If root level is `INFO`, only `INFO`, `WARN`, `ERROR` are logged.
Setting to `DEBUG` enables all levels.

---

## ✅ Summary — Best Practices

| Practice                  | Do This                                     | Avoid This                            |
| ------------------------- | ------------------------------------------- | ------------------------------------- |
| Use SLF4J + Logback       | ✅ `logger.info("App started")`              | ❌ `System.out.println("App started")` |
| Separate environments     | ✅ `logback-dev.xml` vs `logback.xml`        | ❌ One file for both                   |
| Use structured messages   | ✅ `logger.warn("Missing key: {}", keyName)` | ❌ String concatenation + prints       |
| Log only what matters     | ✅ Exceptions, startup, user actions         | ❌ Internal chatter in prod            |
| Let Logback filter levels | ✅ Root level set by config                  | ❌ Manual `if (isDev())` checks        |

---

## 🚀 TL;DR

* **Never use `System.err` or `System.out` again.**
* Use `logger.trace/debug/info/warn/error()` depending on context.
* Let `logback.xml` control visibility.
* Configure verbosity per environment, not per code block.

---

### 📘 Summary Table: When to Use Each Log Level

| Category                    | Example                             | Log Level     |
| --------------------------- | ----------------------------------- | ------------- |
| System startup/shutdown     | “Server started on port 8080”       | INFO          |
| Security or access events   | “User login failed”                 | WARN or ERROR |
| Expected recoverable issues | “Retrying DB connection”            | WARN          |
| Exception stack traces      | “NullPointerException in DAO layer” | ERROR         |
| Debugging internal logic    | “Parsed 25 rows from CSV”           | DEBUG         |
| Deep trace instrumentation  | “Entering method proce              |               |
