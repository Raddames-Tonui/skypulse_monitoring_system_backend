**Notification Processor Task - Detailed Documentation**

---

### 1. Overview

The `NotificationProcessorTask` is a scheduled task in the SkyPulse monitoring system responsible for processing pending notification events stored in the `event_outbox` table. It retrieves events, resolves recipients, loads templates, sends notifications, and logs history.

Key Utilities:

* **RecipientResolver**: Determines who should receive notifications based on the event type.
* **TemplateLoader**: Handles loading of notification templates from multiple sources (DB, filesystem, classpath) with hybrid fallback.

---

### 2. Event Processing Flow

#### 2.1 Fetch Pending Events

* Task queries the `event_outbox` table for events with `status = 'PENDING'`:

```sql
SELECT event_outbox_id, service_id, event_type, payload, first_failure_at
FROM event_outbox
WHERE status = 'PENDING'
FOR UPDATE SKIP LOCKED
LIMIT 50
```

* Each event is locked for processing (`FOR UPDATE SKIP LOCKED`) to prevent multiple workers from handling the same event.

#### 2.2 Parse Payload

* Payload is stored as JSON.
* Using `JsonUtil.mapper()`, the task converts JSON into a `Map<String,Object>`.
* Handles both single object and arrays of payload objects.

#### 2.3 Event Cooldown & Recovery

* **SERVICE_DOWN**:

  * If `first_failure_at` is set, check if the cooldown period (`SystemDefaults.notificationCooldownMinutes`) has passed.
  * If still in cooldown, skip sending.
  * Otherwise, mark `first_failure_at` with `NOW()`.
* **SERVICE_RECOVERED**:

  * Calculate downtime in seconds using `Duration.between(first_failure_at, now)` and inject into payload.

#### 2.4 Load Templates

* Query `notification_templates`:

```sql
SELECT subject_template, body_template, body_template_key, storage_mode
FROM notification_templates
WHERE event_type = ?
```

* **TemplateLoader** determines final content based on `storage_mode`:

**Storage Modes:**

1. `database` - use `body_template` directly.
2. `filesystem` - read from external folder (`notification.templates.path` or default `./templates`).
3. `hybrid` - load order: filesystem → classpath (`resources/templates`) → DB fallback.

* Mustache templates are compiled and rendered with payload data to produce final `subject` and `body`.

#### 2.5 Resolve Recipients

* `RecipientResolver.resolveRecipients` is used:

  * **USER_CREATED/RESET_PASSWORD** → single user email.
  * **UPTIME_REPORTS** → all primary email contacts.
  * **Other service notifications** → service contact group members.

#### 2.6 Send Notifications

* Iterate through recipients.
* Send via `NotificationSender` (e.g., `MultiChannelSender`) which can include email, Telegram, SMS, etc.
* Retry sending up to `SystemDefaults.notificationRetryCount`, waiting `SystemDefaults.uptimeRetryDelay` seconds between attempts.
* Inline images (like logos) can be included in email messages.

#### 2.7 Log Notification History

* After each attempt, insert a record into `notification_history`:

```sql
INSERT INTO notification_history(
    service_id,
    contact_group_id,
    contact_group_member_id,
    notification_channel_id,
    recipient,
    subject,
    message,
    status,
    sent_at,
    error_message
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)
```

* Status: `sent` or `failed`.
* Error message captured if sending failed.

#### 2.8 Mark Event Processed/Failed

* If all notifications for the event succeed:

```sql
UPDATE event_outbox SET status='PROCESSED', updated_at=NOW() WHERE event_outbox_id=?
```

* If any fail or exception occurs:

```sql
UPDATE event_outbox SET status='FAILED', updated_at=NOW(), payload = payload || to_jsonb(?::text) WHERE event_outbox_id=?
```

* JSON error details are appended to payload.

---

### 3. Utilities

#### 3.1 RecipientResolver

* **Purpose**: Determine recipients of notifications based on event type and service.
* Methods:

  * `resolveRecipients(Connection conn, String eventType, long serviceId, Object payloadUserId)` → returns `List<Recipient>`
  * Specialized handling for:

    * USER_CREATED / RESET_PASSWORD
    * UPTIME_REPORTS
    * SERVICE_DOWN, SSL_EXPIRING, SERVICE_RECOVERED, etc.
* Recipient record: `(userId, type, value)`

#### 3.2 TemplateLoader

* **Purpose**: Load notification templates using storage mode and key.
* Methods:

  * `load(String storageMode, String dbTemplate, String templateKey)` → returns `String`
* Supports storage modes:

  1. `database`
  2. `filesystem`
  3. `hybrid` (tries filesystem → classpath → database)
* External path configurable via system property: `notification.templates.path`.
* Logs template loading status using SLF4J.

---

### 4. Summary Flow Diagram

```
[Event_Outbox] --> parse payload --> check cooldown/recovery --> load template --> resolve recipients --> send notifications
                                                                       |                                      |
                                                                       v                                      v
                                                             TemplateLoader (DB/FS/Classpath)        RecipientResolver
                                                                       |                                      |
                                                                       v                                      v
                                                              Rendered Subject & Body                   List<Recipients>
                                                                       \                                   /
                                                                        v                                 v
                                                                       Send via NotificationSender (MultiChannel)
                                                                                       |
                                                                                       v
                                                                            Insert into notification_history
                                                                                       |
                                                                                       v
                                                                       Mark event_outbox as PROCESSED/FAILED
```

---

### 5. Key Notes

* All database interactions are within transactions (`conn.setAutoCommit(false)`), rolled back on exceptions.
* Uses Mustache for template rendering.
* Handles multiple recipients and retry logic.
* Designed for high reliability: pending events locked, retries, fallback templates, error logging.
* Template storage and resolution is fully flexible using TemplateLoader.

---

**End of Documentation**
