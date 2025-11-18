# HikariCP Connection Pooling — Practical Guide + Dynamic Tuning

A concise, practical reference for configuring HikariCP in production. Includes parameter explanations, DataSource vs Connection, recommended defaults, and formulas + code for dynamic tuning based on CPU cores and expected concurrency.

---

## 1) What HikariCP Does (at a glance)

Opening/closing a DB connection per request is slow. HikariCP maintains a **pool** of ready connections:

* Your code borrows a connection, uses it briefly, then **returns** it to the pool.
* The pool size and lifecycle policies determine latency, stability, and DB load.

---

## 2) Configuration Fields Explained

```xml
<ConnectionPool>
    <maximumPoolSize mode="TEXT">10</maximumPoolSize>
    <minimumIdle mode="TEXT">2</minimumIdle>
    <idleTimeout mode="TEXT">60000</idleTimeout>
    <connectionTimeout mode="TEXT">30000</connectionTimeout>
    <maxLifetime mode="TEXT">1800000</maxLifetime>
</ConnectionPool>
```

### maximumPoolSize

* **Meaning:** Max total open connections managed by the pool.
* **Behavior:** If all are busy, callers wait up to `connectionTimeout` for one to free.
* **Rule of thumb:** Avoid setting higher than your DB can handle. Start modest, scale with data.

### minimumIdle

* **Meaning:** Minimum number of **idle** (ready) connections kept warm.
* **Behavior:** HikariCP replenishes idle connections up to this floor.
* **Use:** Improves burst handling (no cold-start penalty).

### idleTimeout (ms)

* **Meaning:** How long an idle connection may sit before being closed (when above `minimumIdle`).
* **Typical:** 30–300s. Lower saves resources; higher avoids churn.

### connectionTimeout (ms)

* **Meaning:** How long to wait for a connection from the pool before failing.
* **Typical:** 10–30s. Too high masks bottlenecks; too low causes false failures.

### maxLifetime (ms)

* **Meaning:** Hard cap on a connection’s age. Older ones are retired & replaced.
* **Typical:** 30–60 min. Keeps connections fresh to avoid DB/network idiosyncrasies.

---

## 3) DataSource vs Connection (in Hikari)

* **DataSource:** A factory configured with JDBC URL, credentials, and pool settings. You call `getConnection()` on it.
* **Connection:** A single session to the DB. In a pool, `close()` **returns** it to the pool rather than closing the socket.

Minimal example:

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
cfg.setUsername("admin");
cfg.setPassword("secret");
cfg.setMaximumPoolSize(10);
cfg.setMinimumIdle(2);
cfg.setConnectionTimeout(30_000);
cfg.setIdleTimeout(60_000);
cfg.setMaxLifetime(1_800_000);

HikariDataSource ds = new HikariDataSource(cfg);

try (Connection c = ds.getConnection()) {
        // use connection, then .close() returns it to pool
        }
```

---

## 4) Recommended Starting Defaults (small/medium services)

* `maximumPoolSize`: **8–32**
* `minimumIdle`: **20–50%** of maximum
* `idleTimeout`: **60–300s**
* `connectionTimeout`: **10–30s**
* `maxLifetime`: **30–60 min** (set slightly lower than DB-side `idle_in_transaction_session_timeout` and any network idle killers)

> Always check your database limits (e.g., Postgres `max_connections`) and leave headroom for admin tools, migrations, and background jobs.

---

## 5) Dynamic Tuning Recipes

### 5.1 CPU-Core Heuristic

Good when you don’t know typical query time but want a safe baseline.

**Formula:**

```
maximumPoolSize ≈ min(DB_MAX_HEADROOM,
                      (available_cores × 2) + IO_wait_factor)
```

* `available_cores` = `Runtime.getRuntime().availableProcessors()`
* `IO_wait_factor` = 0–8 depending on how IO-bound your app is (start with 4)
* `DB_MAX_HEADROOM` = DB limit minus a safety margin (e.g., `max_connections - 20`)

**Set** `minimumIdle ≈ 0.3–0.6 × maximumPoolSize`

### 5.2 Little’s Law (Throughput-Centric)

Use when you know expected traffic and average DB time per request.

**Little’s Law:** `WIP = λ × T`

* `λ` (lambda) = requests/sec that need DB connections (qps touching DB)
* `T` = average time a connection is **held** (s)

**Pool size target:**

```
maximumPoolSize ≈ (qps_db × avg_connection_time_seconds) × safety_factor
```

* `safety_factor`: 1.2–2.0 (start 1.5)
* Example: 150 qps * 0.06 s * 1.5 ≈ **14** → choose **16**

### 5.3 Cap by Database Limits

Ensure:

```
maximumPoolSize + other_app_pools + admin_sessions ≤ postgres.max_connections - 10
```

Reserve for maintenance/migrations.

### 5.4 Java Example: Auto-Tune on Boot

```java
public final class HikariTuner {
    public static void apply(HikariConfig cfg, int dbMaxConnections, int reserved, boolean ioBound) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int ioFactor = ioBound ? 4 : 2; // tweak as needed

        int headroom = Math.max(4, dbMaxConnections - reserved);
        int cpuHeuristic = (cores * 2) + ioFactor;

        int maxPool = Math.min(headroom, Math.max(8, cpuHeuristic));
        int minIdle = Math.max(2, (int)Math.round(maxPool * 0.4));

        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(minIdle);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(120_000);
        cfg.setMaxLifetime(1_800_000); // 30 min; set < DB-side timeouts
    }
}
```

**Usage:**

```java
HikariConfig cfg = new HikariConfig();
// set JDBC, creds...
HikariTuner.apply(cfg, /*dbMaxConnections*/ 300, /*reserved*/ 30, /*ioBound*/ true);
HikariDataSource ds = new HikariDataSource(cfg);
```

---

## 6) Operational Guidance (measure & iterate)

**Monitor key metrics:**

* Pool utilization: active vs idle
* Waits for connection and 99th percentile wait time
* Time a connection is checked out
* DB-side: lock waits, slow queries, `max_connections`, CPU, I/O

**Adjustments:**

* If threads frequently **wait** for a connection → raise `maximumPoolSize` (if DB allows) or reduce connection hold time (optimize queries/transactions).
* If DB shows high CPU/lock contention → lower pool size or optimize queries/indexes.
* If connections churn (frequent open/close) → raise `minimumIdle` or `idleTimeout`.

---

## 7) Postgres & Infrastructure Tips

* **Postgres settings:** Ensure `max_connections` comfortably exceeds total app pools + maintenance headroom. Prefer **PgBouncer** in transaction mode for very high concurrency.
* **Timeouts:** Align Hikari `maxLifetime` < DB `idle_in_transaction_session_timeout` and any LB/firewall idle timeouts.
* **Transactions:** Keep them **short**. Hold connections only around the actual DB work.
* **Prepared statements:** Use server-side prepares judiciously; keep an eye on `max_prepared_transactions` if XA/two-phase is in play.

---

## 8) Quick Checklist

* [ ] Know your DB `max_connections` and reserve headroom
* [ ] Compute initial pool size via cores **or** Little’s Law
* [ ] Set `minIdle` to ~40% of max
* [ ] Keep `maxLifetime` < DB/network idle killers
* [ ] Monitor and iterate (p99 wait, checkout time, DB load)

---

## 9) Copy‑Paste XML & Java Snippets

**XML block:**

```xml
<ConnectionPool>
    <maximumPoolSize mode="TEXT">16</maximumPoolSize>
    <minimumIdle mode="TEXT">6</minimumIdle>
    <idleTimeout mode="TEXT">120000</idleTimeout>
    <connectionTimeout mode="TEXT">30000</connectionTimeout>
    <maxLifetime mode="TEXT">1800000</maxLifetime>
</ConnectionPool>
```

**Java creation:**

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/app");
cfg.setUsername("appuser");
cfg.setPassword("secret");
HikariTuner.apply(cfg, 300, 30, true);
HikariDataSource ds = new HikariDataSource(cfg);
```

---

### Bottom line

Start conservative, cap by DB limits, and iterate with real metrics. Dynamic tuning using CPU cores or Little’s Law gives you a fast, stable baseline — then let observability guide the final tweaks.
# HikariCP Connection Pooling — Practical Guide + Dynamic Tuning

A concise, practical reference for configuring HikariCP in production. Includes parameter explanations, DataSource vs Connection, recommended defaults, and formulas + code for dynamic tuning based on CPU cores and expected concurrency.

---

## 1) What HikariCP Does (at a glance)

Opening/closing a DB connection per request is slow. HikariCP maintains a **pool** of ready connections:

* Your code borrows a connection, uses it briefly, then **returns** it to the pool.
* The pool size and lifecycle policies determine latency, stability, and DB load.

---

## 2) Configuration Fields Explained

```xml
<ConnectionPool>
  <maximumPoolSize mode="TEXT">10</maximumPoolSize>
  <minimumIdle mode="TEXT">2</minimumIdle>
  <idleTimeout mode="TEXT">60000</idleTimeout>
  <connectionTimeout mode="TEXT">30000</connectionTimeout>
  <maxLifetime mode="TEXT">1800000</maxLifetime>
</ConnectionPool>
```

### maximumPoolSize

* **Meaning:** Max total open connections managed by the pool.
* **Behavior:** If all are busy, callers wait up to `connectionTimeout` for one to free.
* **Rule of thumb:** Avoid setting higher than your DB can handle. Start modest, scale with data.

### minimumIdle

* **Meaning:** Minimum number of **idle** (ready) connections kept warm.
* **Behavior:** HikariCP replenishes idle connections up to this floor.
* **Use:** Improves burst handling (no cold-start penalty).

### idleTimeout (ms)

* **Meaning:** How long an idle connection may sit before being closed (when above `minimumIdle`).
* **Typical:** 30–300s. Lower saves resources; higher avoids churn.

### connectionTimeout (ms)

* **Meaning:** How long to wait for a connection from the pool before failing.
* **Typical:** 10–30s. Too high masks bottlenecks; too low causes false failures.

### maxLifetime (ms)

* **Meaning:** Hard cap on a connection’s age. Older ones are retired & replaced.
* **Typical:** 30–60 min. Keeps connections fresh to avoid DB/network idiosyncrasies.

---

## 3) DataSource vs Connection (in Hikari)

* **DataSource:** A factory configured with JDBC URL, credentials, and pool settings. You call `getConnection()` on it.
* **Connection:** A single session to the DB. In a pool, `close()` **returns** it to the pool rather than closing the socket.

Minimal example:

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
cfg.setUsername("admin");
cfg.setPassword("secret");
cfg.setMaximumPoolSize(10);
cfg.setMinimumIdle(2);
cfg.setConnectionTimeout(30_000);
cfg.setIdleTimeout(60_000);
cfg.setMaxLifetime(1_800_000);

HikariDataSource ds = new HikariDataSource(cfg);

try (Connection c = ds.getConnection()) {
  // use connection, then .close() returns it to pool
}
```

---

## 4) Recommended Starting Defaults (small/medium services)

* `maximumPoolSize`: **8–32**
* `minimumIdle`: **20–50%** of maximum
* `idleTimeout`: **60–300s**
* `connectionTimeout`: **10–30s**
* `maxLifetime`: **30–60 min** (set slightly lower than DB-side `idle_in_transaction_session_timeout` and any network idle killers)

> Always check your database limits (e.g., Postgres `max_connections`) and leave headroom for admin tools, migrations, and background jobs.

---

## 5) Dynamic Tuning Recipes

### 5.1 CPU-Core Heuristic

Good when you don’t know typical query time but want a safe baseline.

**Formula:**

```
maximumPoolSize ≈ min(DB_MAX_HEADROOM,
                      (available_cores × 2) + IO_wait_factor)
```

* `available_cores` = `Runtime.getRuntime().availableProcessors()`
* `IO_wait_factor` = 0–8 depending on how IO-bound your app is (start with 4)
* `DB_MAX_HEADROOM` = DB limit minus a safety margin (e.g., `max_connections - 20`)

**Set** `minimumIdle ≈ 0.3–0.6 × maximumPoolSize`

### 5.2 Little’s Law (Throughput-Centric)

Use when you know expected traffic and average DB time per request.

**Little’s Law:** `WIP = λ × T`

* `λ` (lambda) = requests/sec that need DB connections (qps touching DB)
* `T` = average time a connection is **held** (s)

**Pool size target:**

```
maximumPoolSize ≈ (qps_db × avg_connection_time_seconds) × safety_factor
```

* `safety_factor`: 1.2–2.0 (start 1.5)
* Example: 150 qps * 0.06 s * 1.5 ≈ **14** → choose **16**

### 5.3 Cap by Database Limits

Ensure:

```
maximumPoolSize + other_app_pools + admin_sessions ≤ postgres.max_connections - 10
```

Reserve for maintenance/migrations.

### 5.4 Java Example: Auto-Tune on Boot

```java
public final class HikariTuner {
  public static void apply(HikariConfig cfg, int dbMaxConnections, int reserved, boolean ioBound) {
    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    int ioFactor = ioBound ? 4 : 2; // tweak as needed

    int headroom = Math.max(4, dbMaxConnections - reserved);
    int cpuHeuristic = (cores * 2) + ioFactor;

    int maxPool = Math.min(headroom, Math.max(8, cpuHeuristic));
    int minIdle = Math.max(2, (int)Math.round(maxPool * 0.4));

    cfg.setMaximumPoolSize(maxPool);
    cfg.setMinimumIdle(minIdle);
    cfg.setConnectionTimeout(30_000);
    cfg.setIdleTimeout(120_000);
    cfg.setMaxLifetime(1_800_000); // 30 min; set < DB-side timeouts
  }
}
```

**Usage:**

```java
HikariConfig cfg = new HikariConfig();
// set JDBC, creds...
HikariTuner.apply(cfg, /*dbMaxConnections*/ 300, /*reserved*/ 30, /*ioBound*/ true);
HikariDataSource ds = new HikariDataSource(cfg);
```

---

## 6) Operational Guidance (measure & iterate)

**Monitor key metrics:**

* Pool utilization: active vs idle
* Waits for connection and 99th percentile wait time
* Time a connection is checked out
* DB-side: lock waits, slow queries, `max_connections`, CPU, I/O

**Adjustments:**

* If threads frequently **wait** for a connection → raise `maximumPoolSize` (if DB allows) or reduce connection hold time (optimize queries/transactions).
* If DB shows high CPU/lock contention → lower pool size or optimize queries/indexes.
* If connections churn (frequent open/close) → raise `minimumIdle` or `idleTimeout`.

---

## 7) Postgres & Infrastructure Tips

* **Postgres settings:** Ensure `max_connections` comfortably exceeds total app pools + maintenance headroom. Prefer **PgBouncer** in transaction mode for very high concurrency.
* **Timeouts:** Align Hikari `maxLifetime` < DB `idle_in_transaction_session_timeout` and any LB/firewall idle timeouts.
* **Transactions:** Keep them **short**. Hold connections only around the actual DB work.
* **Prepared statements:** Use server-side prepares judiciously; keep an eye on `max_prepared_transactions` if XA/two-phase is in play.

---

## 8) Quick Checklist

* [ ] Know your DB `max_connections` and reserve headroom
* [ ] Compute initial pool size via cores **or** Little’s Law
* [ ] Set `minIdle` to ~40% of max
* [ ] Keep `maxLifetime` < DB/network idle killers
* [ ] Monitor and iterate (p99 wait, checkout time, DB load)

---

## 9) Copy‑Paste XML & Java Snippets

**XML block:**

```xml
<ConnectionPool>
  <maximumPoolSize mode="TEXT">16</maximumPoolSize>
  <minimumIdle mode="TEXT">6</minimumIdle>
  <idleTimeout mode="TEXT">120000</idleTimeout>
  <connectionTimeout mode="TEXT">30000</connectionTimeout>
  <maxLifetime mode="TEXT">1800000</maxLifetime>
</ConnectionPool>
```

**Java creation:**

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/app");
cfg.setUsername("appuser");
cfg.setPassword("secret");
HikariTuner.apply(cfg, 300, 30, true);
HikariDataSource ds = new HikariDataSource(cfg);
```

---

### Bottom line

Start conservative, cap by DB limits, and iterate with real metrics. Dynamic tuning using CPU cores or Little’s Law gives you a fast, stable baseline — then let observability guide the final tweaks.
