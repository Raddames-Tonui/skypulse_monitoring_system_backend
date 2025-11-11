## Notification Sending Flow using the Outbox Pattern

### 1. Purpose

The Outbox Pattern ensures that no notification event is ever lost due to network or service failures. It decouples database transactions (e.g., recording a service outage) from external side effects (e.g., sending an email or Telegram alert).

Without the pattern, if the database write succeeds but the email send fails, alerts can be lost. The Outbox Pattern solves this by persisting every event before dispatch.

---

### 2. How It Works

1. **Event Trigger:**
   When an uptime or SSL check detects an issue (e.g., service DOWN or SSL expiring), an event is created.

2. **Database Transaction:**
   The system writes two records in a single transaction:

   * One in the relevant log table (`uptime_logs`, `ssl_logs`)
   * One in the `event_outbox` table, containing the event details.

3. **Outbox Worker:**
   A background process continuously polls the `event_outbox` table for rows with `status = 'PENDING'`.

4. **Dispatch:**
   The worker reads the event payload, sends the appropriate notification (email, Telegram, SMS, etc.), and updates the record status:

   * `PROCESSING` → when sending begins
   * `SENT` → after successful delivery
   * `FAILED` → if sending fails

5. **Retries:**
   Failed sends are retried based on configuration. The `retries` field counts attempts, and `last_attempt_at` records the last try.

6. **Notification Logging:**
   Once sent, a record is created in `notification_history` for audit purposes. This table keeps the final log of what was actually sent and to whom.

---

### 3. Table Design

```sql
CREATE TABLE event_outbox (
    event_outbox_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type      VARCHAR(100),             -- e.g. SERVICE_DOWN, SSL_EXPIRING
    payload         JSONB NOT NULL,           -- event data (service_id, group_id, message)
    status          VARCHAR(20) DEFAULT 'PENDING',  -- PENDING | PROCESSING | SENT | FAILED
    retries         INT DEFAULT 0,
    last_attempt_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_event_outbox_status ON event_outbox(status);
```

Each row represents a single event waiting for processing.

---

### 4. Example Flow

| Step | Action                   | Table                  | Status     |
| ---- | ------------------------ | ---------------------- | ---------- |
| 1    | Service 9 goes DOWN      | `uptime_logs`          | DOWN       |
| 2    | Insert event             | `event_outbox`         | PENDING    |
| 3    | Worker picks event       |                        | PROCESSING |
| 4    | Sends email successfully | `notification_history` | sent       |
| 5    | Update outbox record     | `event_outbox`         | SENT       |

If sending fails, status becomes `FAILED` and `retries` increments. The worker retries later.

---

### 5. Integration with Other Tables

| Table                     | Role                                     |
| ------------------------- | ---------------------------------------- |
| `uptime_logs`, `ssl_logs` | Generate events to notify                |
| `event_outbox`            | Queues events for reliable delivery      |
| `notification_history`    | Logs all successfully sent notifications |
| `background_tasks`        | Tracks Outbox worker execution           |
| `system_health_logs`      | Reports worker status and errors         |

---

### 6. Benefits

| Without Outbox           | With Outbox                                         |
| ------------------------ | --------------------------------------------------- |
| Alerts can fail silently | Events always stored before sending                 |
| No retries on failure    | Automatic retry mechanism                           |
| Inconsistent states      | Guaranteed consistency between DB and notifications |
| Hard to debug            | Full audit trail of all events                      |

---

### 7. Summary

The Outbox Pattern ensures **reliable, atomic, and traceable notification delivery** across all channels.
It bridges the gap between internal transactions and external systems, ensuring every alert is eventually delivered, even if the system crashes or networks fail.
