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