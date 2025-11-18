| Category            | Key Tables                                                                                                                    | Purpose                                              |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| **Auth & Security** | `users`, `roles`, `permissions`, `login_attempts`, `blocked_ips`, `user_audit_session`, `captcha_sessions`                    | Authentication, authorization, rate limiting         |
| **Monitoring**      | `monitored_services`, `uptime_logs`, `ssl_logs`, `incidents`, `maintenance_windows`, `system_health_logs`, `background_tasks` | Uptime, SSL, incidents, maintenance, system status   |
| **Notifications**   | `contact_groups`, `notification_channels`, `notification_templates`, `notification_history`, `event_outbox`                   | Alerts, messages, and reliability                    |
| **Reporting**       | `uptime_summaries`, `audit_log`                                                                                               | Historical performance and system actions            |
| **Settings**        | `system_settings`, `user_preferences`, `form_definitions`                                                                     | Configurations, UI preferences, dynamic form builder |



| Table                | Purpose                                                    |
| -------------------- | ---------------------------------------------------------- |
| ✅ `login_attempts`   | track successes/failures, power CAPTCHA escalation         |
| ✅ `blocked_ips`      | temporarily block repeated brute-force IPs                 |
| ✅ `captcha_sessions` | manage per-IP CAPTCHA tokens with expiry                   |
| ✅ `event_outbox`     | ensure reliable event delivery (for notifications, alerts) |
| ✅ `background_tasks` | manage & monitor periodic job executions                   |



No — background_tasks and system_health_logs serve different layers of observability, even though both mention “status” and “last checked.”
Here’s the key difference and why you should keep both 👇

⚙️ 1️⃣ system_health_logs → "Runtime Health Snapshot"

Purpose:
To continuously track the current health state of each internal system component or module.

Think of it as a health heartbeat table.
It answers:

“Is my SSL checker currently running fine?”
“Did the uptime monitor fail last cycle?”

Characteristics

Aspect	Description
Scope	Component-level (“SSL Checker”, “Notification Worker”, “Uptime Monitor”)
Frequency	Updated frequently (every few seconds/minutes by each worker).
Lifecycle	One row per component; it’s continuously updated, not appended.
Usage	Used for dashboards (like /system/health) or admin UI status indicators.
Analogy	Like Prometheus “up” metrics or a heartbeat check.

Example Row

component	status	message	last_checked
ssl_checker	OK	Checked 50 certs in last run	2025-11-11 09:00
uptime_worker	FAILING	Timeout on 3 services	2025-11-11 09:01
🔁 2️⃣ background_tasks → "Job Execution History & Scheduling"

Purpose:
To audit, schedule, and track execution results of each individual background job or cron task.

It answers:

“When did the last uptime_check job actually run?”
“Did the SSL expiry cleanup fail?”

Characteristics

Aspect	Description
Scope	Task instance-level (“Run #124 of SSL Checker”)
Frequency	Inserted or updated on each job run.
Lifecycle	Historical — you keep many rows per recurring job.
Usage	Auditing, troubleshooting failed jobs, viewing run durations.
Analogy	Like a job history table in Airflow / cron logs.

Example Row

task_name	task_type	status	last_run_at	next_run_at	error_message
SSL Expiry Checker	ssl_check	SUCCESS	2025-11-11 09:00	2025-11-11 10:00	NULL
Uptime Poller	uptime_check	FAILED	2025-11-11 08:59	2025-11-11 09:04	Connection timeout
🧭 3️⃣ In Simple Terms
Table	What it tracks	Data style	Used by
🩺 system_health_logs	The current health of long-running components (status snapshot).	Overwritten per component	Monitoring dashboards
🧰 background_tasks	The history and schedule of periodic or ad-hoc jobs.	Appends new row per run	Job manager / audit UI
✅ Keep both because:

system_health_logs shows live operational health (is it alive?).

background_tasks shows temporal reliability (did it keep running correctly over time?).

🧩 Integration Example

When your uptime worker executes:

It logs its start and end into background_tasks.

It updates its “I’m alive” status in system_health_logs.

If it fails, both reflect that failure for short-term and long-term visibility.












| Area                                                            | Covered? | Notes                                      |
| --------------------------------------------------------------- | -------- | ------------------------------------------ |
| Users, Roles, Permissions                                       | ✅        | Complete and normalized.                   |
| Audit Sessions & Login Attempts                                 | ✅        | Excellent — covers security visibility.    |
| Notifications (Templates, History, Channels)                    | ✅        | Full-featured and production-grade.        |
| Monitoring Core (Services, SSL, Uptime, Incidents, Maintenance) | ✅        | Covers all key aspects of uptime tracking. |
| Event Outbox                                                    | ✅        | Ensures reliable async dispatch.           |
| Background Tasks                                                | ✅        | Complements Outbox for job control.        |
| Dynamic Forms                                                   | ✅        | Extensible front-end form metadata system. |
| System Settings                                                 | ✅        | Centralized configuration management.      |
| Audit Log                                                       | ✅        | Covers actions, changes, IPs.              |





🧠 Missing (or Worth Adding)

Here’s the short list of what’s left to make the design airtight for a real production system:


| New Table                            | Purpose                                                                                                                        | Example Use                                                    |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------- |
| **`blocked_ips`**                    | Track temporarily or permanently blocked IPs after repeated failed logins (prevents brute-force).                              | Prevent 10+ login attempts from same IP in 10 min.             |
| **`captcha_sessions`**               | Manage CAPTCHA tokens and expiry (for login bot-prevention).                                                                   | Store token and 6-char captcha string, expire after 2 minutes. |
| **`error_logs`**                     | Store internal application errors not tied to users. Useful for backend debugging, e.g., failed background jobs or API errors. | Caught exceptions, stack traces, or unexpected job failures.   |
| **`api_keys`** *(optional)*          | Allow service-to-service access without user logins (e.g., Slack bot integration, webhook triggers).                           | External integrations or automation scripts.                   |
| **`ip_location_cache`** *(optional)* | Cache GeoIP lookups for audit logs or sessions.                                                                                | Store resolved country/region per IP for analytics.            |



| Table               | Adds Value In                               |
| ------------------- | ------------------------------------------- |
| `blocked_ips`       | Security hardening + brute-force prevention |
| `captcha_sessions`  | Bot protection for login forms              |
| `error_logs`        | Observability + debugging backend issues    |
| `api_keys`          | Future automation/integration support       |
| `ip_location_cache` | Enhancing audit/session data analytics      |




-- Track blocked IPs due to brute-force or abuse
CREATE TABLE blocked_ips (
    blocked_ip_id BIGSERIAL PRIMARY KEY,
    ip_address    VARCHAR(64) UNIQUE NOT NULL,
    reason        TEXT,
    blocked_until TIMESTAMP,      -- null = permanent block
    date_created  TIMESTAMP DEFAULT NOW(),
    date_modified TIMESTAMP DEFAULT NOW()
);

-- CAPTCHA sessions to prevent bots
CREATE TABLE captcha_sessions (
    captcha_session_id BIGSERIAL PRIMARY KEY,
    ip_address         VARCHAR(64),
    captcha_token      VARCHAR(64) UNIQUE,  -- reference token
    captcha_text       VARCHAR(10),
    expires_at         TIMESTAMP DEFAULT NOW() + INTERVAL '2 minutes',
    created_at         TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_captcha_ip ON captcha_sessions(ip_address);

-- Error logs for backend/job exceptions
CREATE TABLE error_logs (
    error_log_id  BIGSERIAL PRIMARY KEY,
    component     VARCHAR(100),       -- e.g., ssl_checker, smtp_worker
    severity      VARCHAR(20),        -- INFO, WARN, ERROR, CRITICAL
    message       TEXT,
    stack_trace   TEXT,
    occurred_at   TIMESTAMP DEFAULT NOW(),
    date_created  TIMESTAMP DEFAULT NOW()
);

-- Optional: API Keys for integrations (machine access)
CREATE TABLE api_keys (
    api_key_id     BIGSERIAL PRIMARY KEY,
    api_key_hash   TEXT UNIQUE NOT NULL,     -- store hashed value only
    owner_user_id  BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    name           VARCHAR(100),
    scopes         JSONB DEFAULT '{}'::jsonb,   -- permissions
    expires_at     TIMESTAMP,
    last_used_at   TIMESTAMP,
    date_created   TIMESTAMP DEFAULT NOW(),
    date_modified  TIMESTAMP DEFAULT NOW(),
    is_active      BOOLEAN DEFAULT TRUE
);

-- Optional: IP → location cache for sessions
CREATE TABLE ip_location_cache (
    ip_address     VARCHAR(64) PRIMARY KEY,
    country        VARCHAR(100),
    region         VARCHAR(100),
    city           VARCHAR(100),
    last_checked   TIMESTAMP DEFAULT NOW()
);
