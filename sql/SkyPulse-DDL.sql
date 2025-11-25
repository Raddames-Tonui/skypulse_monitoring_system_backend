-- SkyPulse Monitoring System
-- CREATE DATABASE skypulse_monitoring_system_database;

-- EXTENSIONS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- UUID generation
CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- passwords, tokens encryption

-- Generic "updated timestamp" trigger for date_modified
CREATE OR REPLACE FUNCTION touch_date_modified()
RETURNS trigger AS $$
BEGIN
  NEW.date_modified := NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- 1) USERS, ROLES,
CREATE TABLE company (
    company_id        INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_name      VARCHAR(250) UNIQUE NOT NULL,
    company_description VARCHAR(250),
    date_created      TIMESTAMP DEFAULT NOW(),
    date_modified     TIMESTAMP DEFAULT NOW()
);

CREATE TRIGGER trg_company_touch_modified
BEFORE UPDATE ON company
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();


CREATE TABLE roles (
    role_id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_name        VARCHAR(10) UNIQUE NOT NULL DEFAULT 'VIEWER',  -- e.g. admin, operator, viewer
    role_description VARCHAR(250),
    date_created     TIMESTAMP DEFAULT NOW(),
    date_modified    TIMESTAMP DEFAULT NOW()
);


CREATE TRIGGER trg_roles_touch_modified
BEFORE UPDATE ON roles
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();



CREATE TABLE users (
    user_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid           UUID UNIQUE DEFAULT gen_random_uuid(),
    first_name     VARCHAR(20),
    last_name      VARCHAR(20),
    user_email     VARCHAR(150) UNIQUE NOT NULL,
    password_hash  TEXT NOT NULL,
    role_id        INTEGER NOT NULL,
    is_active      BOOLEAN DEFAULT TRUE,
    company_id     INTEGER,
    date_created   TIMESTAMP DEFAULT NOW(),
    date_modified  TIMESTAMP DEFAULT NOW(),
    is_deleted     BOOLEAN DEFAULT FALSE,
    deleted_at     TIMESTAMP,

    CONSTRAINT fk_roles_users_role_id
      FOREIGN KEY (role_id) REFERENCES roles(role_id) ON UPDATE CASCADE,

    CONSTRAINT fk_company_users_company_id
      FOREIGN KEY (company_id) REFERENCES company(company_id)
);


CREATE INDEX idx_users_email ON users(user_email);
CREATE INDEX idx_users_role_id ON users(role_id);

CREATE TRIGGER trg_users_touch_modified
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();


CREATE TABLE user_preferences (
    user_preference_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT      NOT NULL UNIQUE,
    alert_channel      VARCHAR(30) DEFAULT 'email', -- 'email', 'telegram', 'sms'
    receive_weekly_reports BOOLEAN DEFAULT TRUE,
    language           VARCHAR(10) DEFAULT 'en',
    timezone           VARCHAR(100) DEFAULT 'UTC',
    dashboard_layout   JSONB DEFAULT '{}'::jsonb,
    date_created       TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_user_preferences
        FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);


CREATE TABLE user_contacts (
    user_contacts_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT,
    type             VARCHAR(20) CHECK (type IN ('email', 'phone', 'sms', 'telegram', 'slack', 'teams')),
    value            VARCHAR(150) NOT NULL,
    verified         BOOLEAN DEFAULT FALSE,
    is_primary       BOOLEAN DEFAULT FALSE,
    date_created     TIMESTAMP DEFAULT NOW(),
    date_modified    TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_user_contacts
        FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX index_user_contacts_user_id ON user_contacts(user_id);


-- AUTH_SESSIONS: Stores refresh tokens, devices, JWT identifiers, and audit info
CREATE TABLE auth_sessions (
    auth_session_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL,
    refresh_token_hash TEXT NOT NULL,
    jwt_id UUID NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    login_time TIMESTAMP DEFAULT NOW(),
    logout_time TIMESTAMP,
    ip_address VARCHAR(64),
    user_agent TEXT,
    device_name VARCHAR(150),
    nearest_location VARCHAR(50),
    session_status VARCHAR(20) DEFAULT 'active', -- active, expired, revoked
    is_revoked BOOLEAN DEFAULT FALSE,
    revoked_at TIMESTAMP,
    replaced_by UUID,
    replaced_at TIMESTAMP,
    date_created TIMESTAMP DEFAULT NOW(),
    date_modified TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_users_auth_sessions FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_jwt_id ON auth_sessions(jwt_id);
CREATE INDEX idx_auth_sessions_refresh_hash ON auth_sessions(refresh_token_hash);


CREATE TABLE login_failures (
    login_failure_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT,
    user_email VARCHAR(150),
    ip_address VARCHAR(64),
    user_agent TEXT,
    reason TEXT,
    attempted_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT fk_users_login_failures FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

CREATE INDEX idx_login_failures_user_email ON login_failures(user_email);
CREATE INDEX idx_login_failures_ip_time ON login_failures(ip_address, attempted_at DESC);



-- 2) CONTACT GROUPS & NOTIFICATIONS

CREATE TABLE contact_groups (
    contact_group_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid                        UUID UNIQUE DEFAULT gen_random_uuid(),
    contact_group_name          VARCHAR(25) UNIQUE NOT NULL, -- eg 'support team', 'backend team'
    contact_group_description   TEXT,
    created_by                  BIGINT,
    date_created                TIMESTAMP DEFAULT NOW(),
    date_modified               TIMESTAMP DEFAULT NOW(),
    is_deleted                  BOOLEAN DEFAULT FALSE,
    deleted_at                  TIMESTAMP,
    CONSTRAINT fk_users_contact_groups_created_by
        FOREIGN KEY (created_by) REFERENCES users(user_id)
);

CREATE TRIGGER trg_contact_groups_touch_modified
BEFORE UPDATE ON contact_groups
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Link registered users to groups
CREATE TABLE contact_group_members (
  contact_group_member_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contact_group_id           BIGINT NOT NULL,
  user_id                    BIGINT NOT NULL,
  is_primary                 BOOLEAN DEFAULT FALSE,
  added_at                   TIMESTAMP DEFAULT NOW(),
  date_created               TIMESTAMP DEFAULT NOW(),
  date_modified              TIMESTAMP DEFAULT NOW(),
  UNIQUE (contact_group_id, user_id),

  CONSTRAINT fk_contact_groups_contact_group_members_group_id
     FOREIGN KEY (contact_group_id) REFERENCES contact_groups(contact_group_id) ON DELETE CASCADE,

  CONSTRAINT fk_users_contact_group_members_user_id
     FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX index_contact_group_members_group_id
    ON contact_group_members(contact_group_id);

-- Global channels (Email, Telegram, SMS, Teams)
CREATE TABLE notification_channels (
  notification_channel_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  notification_channel_code        VARCHAR(50) UNIQUE NOT NULL, -- EMAIL  TELEGRAM  SMS
  notification_channel_name        VARCHAR(50),
  is_enabled                       BOOLEAN DEFAULT TRUE,
  date_created                     TIMESTAMP DEFAULT NOW(),
  date_modified                    TIMESTAMP DEFAULT NOW()
);



-- Customizable message templates
CREATE TABLE notification_templates (
    notification_template_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid                        UUID UNIQUE DEFAULT gen_random_uuid(),
    event_type                  VARCHAR(50), -- service_down, ssl_expiry...
    storage_mode                VARCHAR(20) DEFAULT 'hybrid'
                                    CHECK (storage_mode IN ('database', 'filesystem', 'hybrid')),
    subject_template            TEXT NOT NULL,
    body_template               TEXT NOT NULL,   -- telegram/SMS body (HTML or plain text)
    pdf_template                TEXT,            -- Optional: for PDF layouts
    include_pdf                 BOOLEAN DEFAULT FALSE,
    body_template_key           VARCHAR(200),    -- e.g. 'emails/service_down_v1.html'
    pdf_template_key            VARCHAR(200),    -- e.g. 'pdf/service_down_v1.html'
    template_syntax             VARCHAR(20) DEFAULT 'mustache', -- Defines placeholder format
    sample_data                 JSONB DEFAULT '{}'::jsonb,      -- Example JSON data for preview
    created_by                  BIGINT REFERENCES users(user_id),
    date_created                TIMESTAMP DEFAULT NOW(),
    date_modified               TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_notification_templates_created_by
        FOREIGN KEY (created_by) REFERENCES users(user_id)
);

CREATE TRIGGER trg_notification_templates_touch_modified
BEFORE UPDATE ON notification_templates
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Log of each send attempt
CREATE TABLE notification_history (
    notification_history_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    service_id                  BIGINT,
    contact_group_id            BIGINT,
    contact_group_member_id     BIGINT,
    notification_channel_id     BIGINT,
    recipient                   VARCHAR(255),
    subject                     TEXT,
    message                     TEXT,
    status                      VARCHAR(20) DEFAULT 'sent', -- sent  failed  pending
    sent_at                     TIMESTAMP DEFAULT NOW(),
    error_message               TEXT,
    include_pdf                 BOOLEAN DEFAULT FALSE,
    pdf_template_id             BIGINT,
    pdf_file_path               TEXT,
    pdf_file_hash               VARCHAR(64),
    pdf_generated_at            TIMESTAMP,
    date_created                TIMESTAMP DEFAULT NOW(),
    date_modified               TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_contact_groups_notification_history_group_id
        FOREIGN KEY (contact_group_id) REFERENCES contact_groups(contact_group_id),

    CONSTRAINT fk_cgm_notification_history_member_id
        FOREIGN KEY (contact_group_member_id) REFERENCES contact_group_members(contact_group_member_id),

    CONSTRAINT fk_channels_notification_history_channel_id
        FOREIGN KEY (notification_channel_id) REFERENCES notification_channels(notification_channel_id),

    CONSTRAINT fk_templates_notification_history_template_id
        FOREIGN KEY (pdf_template_id) REFERENCES notification_templates(notification_template_id)
);



-- 3) MONITORING CORE (SERVICES, UPTIME, SSL, INCIDENTS, MAINTENANCE)

CREATE TABLE monitored_services (
  monitored_service_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uuid                   UUID UNIQUE DEFAULT gen_random_uuid(),
  monitored_service_name VARCHAR(200) NOT NULL,   -- eg. 'mwalimu sacco', 'ndege chai'
  monitored_service_url  TEXT NOT NULL,
  monitored_service_region VARCHAR(100) DEFAULT 'default',
  check_interval         INTEGER,  -- the service is checked every _ minutes
  retry_count            INTEGER,    -- How many attempts made b4 declaring 'DOWN'
  retry_delay            INTEGER,  --  wait time between retry attempts when a check fails in seconds
  expected_status_code   INTEGER DEFAULT 200,
  ssl_enabled            BOOLEAN DEFAULT TRUE,
  last_uptime_status     VARCHAR(10) DEFAULT 'DOWN', -- UP, DOWN,
  consecutive_failures  INTEGER DEFAULT 0,   -- consecutive_failures >= retry_count
  last_checked           TIMESTAMP,
  created_by             BIGINT REFERENCES users(user_id),
  date_created           TIMESTAMP DEFAULT NOW(),
  date_modified          TIMESTAMP DEFAULT NOW(),
  is_active              BOOLEAN DEFAULT TRUE
);

CREATE TABLE monitored_services_contact_groups (
    monitored_service_id BIGINT NOT NULL,
    contact_group_id BIGINT NOT NULL,
    PRIMARY KEY (monitored_service_id, contact_group_id),
    CONSTRAINT fk_monitored_services_monitored_services_contact_groups
        FOREIGN KEY (monitored_service_id) REFERENCES monitored_services(monitored_service_id),
    CONSTRAINT fk_contact_groups_monitored_services_contact_groups
        FOREIGN KEY (contact_group_id) REFERENCES contact_groups(contact_group_id)
);


CREATE TRIGGER trg_monitored_services_touch_modified
BEFORE UPDATE ON monitored_services
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- High-volume logs
CREATE TABLE uptime_logs (
  uptime_log_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  monitored_service_id BIGINT NOT NULL,
  status            VARCHAR(10) NOT NULL, -- UP / DOWN
  response_time_ms  INTEGER,
  http_status       INTEGER,
  error_message     TEXT,
  region            VARCHAR(100),
  checked_at        TIMESTAMP DEFAULT NOW(),
  date_created      TIMESTAMP DEFAULT NOW(),
  date_modified     TIMESTAMP DEFAULT NOW(),
  CONSTRAINT fk_monitored_services_uptime_logs
    FOREIGN KEY (monitored_service_id) REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE
);

CREATE INDEX index_uptime_logs_service_id_checked_at_status
ON uptime_logs(monitored_service_id, checked_at, status);

-- SSL logs: Check on the SSL and log them
CREATE TABLE ssl_logs (
  ssl_log_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  monitored_service_id BIGINT NOT NULL,
  domain              VARCHAR(255),
  issuer              VARCHAR(255),        -- Certificate Authority(CA)
  serial_number       VARCHAR(255),       --
  signature_algorithm VARCHAR(100),       -- e.g., SHA256withRSA
  public_key_algo     VARCHAR(50),        -- e.g., RSA, EC, DSA
  public_key_length   INTEGER,            -- e.g., 2048, 256
  san_list            TEXT,               -- comma-separated Subject Alternative Names (All domains certificate covers)
  chain_valid         BOOLEAN,            -- true if certificate chain is valid
  subject             VARCHAR(500),        -- certificates "Identity" (Common Name, Organization, Location)
  fingerprint         VARCHAR(128),        -- unique hash of entire cert
  issued_date         DATE,
  expiry_date         DATE,
  days_remaining      INTEGER,
  last_checked        TIMESTAMP DEFAULT NOW(),
  retry_count         INT DEFAULT 0,
  date_created        TIMESTAMP DEFAULT NOW(),
  date_modified       TIMESTAMP DEFAULT NOW(),

  CONSTRAINT fk_monitored_services_ssl_logs
    FOREIGN KEY (monitored_service_id) REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,

  CONSTRAINT uniq_ssl_ms_domain UNIQUE (monitored_service_id, domain)
);


-- SSL that have been notified
CREATE TABLE ssl_alerts (
    ssl_alert_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    monitored_service_id BIGINT NOT NULL,
    days_remaining INTEGER NOT NULL,
    sent_at TIMESTAMP DEFAULT NOW(),

    CONSTRAINT uniq_ssl_alert UNIQUE (monitored_service_id, days_remaining),
    CONSTRAINT fk_monitored_services_ssl_alerts
        FOREIGN KEY (monitored_service_id)
        REFERENCES monitored_services(monitored_service_id)
        ON DELETE CASCADE
);


-- Incident tickets
CREATE TABLE incidents (
  incident_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uuid            UUID UNIQUE DEFAULT gen_random_uuid(),
  monitored_service_id BIGINT NOT NULL,
  started_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  resolved_at     TIMESTAMP,
  duration_minutes INTEGER,
  cause           TEXT,
  status          VARCHAR(20) DEFAULT 'open', -- open  resolved
  date_created    TIMESTAMP DEFAULT NOW(),
  date_modified   TIMESTAMP DEFAULT NOW(),
  CONSTRAINT fk_monitored_services_incidents
    FOREIGN KEY (monitored_service_id) REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE
);

CREATE TRIGGER trg_incidents_touch_modified
BEFORE UPDATE ON incidents
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Maintenance windows
--/**
--    -- Skip uptime checks for services covered by an active maintenance window.
--    -- Suppress alerts or notifications (no downtime events).
--        @service_id = 5 - Maintenance only for that specific service.
--        @service_id IS NULL - Maintenance applies to all monitored services — system-wide pause.
--*/
CREATE TABLE maintenance_windows (
  maintenance_window_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uuid                   UUID UNIQUE DEFAULT gen_random_uuid(),
  monitored_service_id   BIGINT NULL,
  start_time             TIMESTAMP NOT NULL,
  end_time               TIMESTAMP NOT NULL,
  reason                 TEXT,
  created_by             BIGINT REFERENCES users(user_id),
  date_created           TIMESTAMP DEFAULT NOW(),
  date_modified          TIMESTAMP DEFAULT NOW(),
  CONSTRAINT fk_monitored_services_maintenance_windows
    FOREIGN KEY (monitored_service_id) REFERENCES monitored_services(monitored_service_id)
);

CREATE INDEX index_maintenance_windows_active
ON maintenance_windows(monitored_service_id, start_time, end_time);

CREATE TRIGGER trg_maintenance_windows_touch_modified
BEFORE UPDATE ON maintenance_windows
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Event outbox
CREATE TABLE event_outbox (
    event_outbox_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid UUID DEFAULT gen_random_uuid(),
    service_id BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
    event_type      VARCHAR(100),                                 -- e.g. SERVICE_DOWN, SSL_EXPIRING
    payload         JSONB NOT NULL,                               -- the actual event data (service_id, message, etc)
    status          VARCHAR(20) DEFAULT 'PENDING',
    first_failure_at    TIMESTAMP,  --
    retries         INT DEFAULT 0,
    last_attempt_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_event_outbox_status ON event_outbox(status);
CREATE INDEX idx_event_outbox_service_id ON event_outbox(service_id);



-- 4) REPORTING & HEALTH
-- Uptime records of a service
CREATE TABLE uptime_summaries (
  uptime_summary_id       BIGINT PRIMARY KEY,
  service_id              BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
  date                    DATE,
  uptime_percentage       NUMERIC(5,2),
  average_response_time   NUMERIC(10,2),
  incidents_count         INTEGER,
  downtime_minutes        INTEGER,
  date_created            TIMESTAMP DEFAULT NOW(),
  date_modified           TIMESTAMP DEFAULT NOW()
);

-- Track execution results of each background job or cron task. eg UptimeCheckTask
CREATE TABLE background_tasks (
    background_task_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_name          VARCHAR(150) UNIQUE,
    task_type          VARCHAR(50),
    status             VARCHAR(20) DEFAULT 'PENDING',
    last_run_at        TIMESTAMP,
    next_run_at        TIMESTAMP,
    error_message      TEXT,
    date_created       TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW()
);


-- logs of health of system components inside SkyPulse
CREATE TABLE system_health_logs (
  system_health_log_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  component             VARCHAR(100),
  status                VARCHAR(50),
  message               TEXT,
  last_checked          TIMESTAMP DEFAULT NOW(),
  date_created          TIMESTAMP DEFAULT NOW(),
  date_modified         TIMESTAMP DEFAULT NOW()
);

-- 5) AUDIT & SETTINGS
--- CRUD Audit performed by user
CREATE TABLE audit_log (
  audit_log_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT,
  entity          VARCHAR(150),
  entity_id       BIGINT,
  action          VARCHAR(50),
  before_data     JSONB,
  after_data      JSONB,
  ip_address      VARCHAR(64),
  date_created    TIMESTAMP DEFAULT NOW(),
  date_modified   TIMESTAMP DEFAULT NOW(),
  CONSTRAINT fk_audit_log_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);


-- Default system settings done by administrator
--/**
-- key                        value (example)  description
-- `default_ping_interval`    `60`             Default interval in seconds for uptime checks
-- `default_retry_count`      `3`              Number of retries before marking DOWN
-- `default_retry_delay`      `5`              Delay between retries in seconds
-- `default_timeout`          `8`              HTTP request timeout in seconds
-- `default_ssl_expiry_days`  `30`             Threshold for SSL expiry alerts
-- `default_alert_channels`   `email,sms`      Default alert channels
--
--*/

CREATE TABLE system_settings (
    system_setting_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key               VARCHAR(150) NOT NULL,       -- category of the setting
    description       TEXT,

    uptime_check_interval INT,      -- seconds
    uptime_retry_count    INT,
    uptime_retry_delay    INT,      -- seconds
    sse_push_interval     INT,      -- seconds

    ssl_check_interval    INT,      -- seconds between SSL checks
    ssl_alert_thresholds  TEXT,     -- e.g., "30,14,7" days
    ssl_retry_count       INT DEFAULT 3,
    ssl_retry_delay       INT DEFAULT 360,     -- seconds

    notification_check_interval   INT,          -- seconds
    notification_retry_count      INT,
    notification_cooldown_minutes INT DEFAULT 10,

    version           INT DEFAULT 1,
    is_active         BOOLEAN DEFAULT TRUE,    -- only one active per key
    changed_by        BIGINT,
    date_created      TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW()
);

-- Ensure only one active version per key
CREATE UNIQUE INDEX ux_system_settings_active
ON system_settings(key);


