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


-- 1) USERS, ROLES, PERMISSIONS
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
    role_name        VARCHAR(20) UNIQUE NOT NULL,  -- e.g. admin | operator | viewer
    role_description VARCHAR(250),
    date_created     TIMESTAMP DEFAULT NOW(),
    date_modified    TIMESTAMP DEFAULT NOW()
);

CREATE TRIGGER trg_roles_touch_modified
BEFORE UPDATE ON roles
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();


CREATE TABLE permissions (
    permission_id           INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_code         VARCHAR(50) UNIQUE NOT NULL,  -- e.g. view_dashboard
    permission_description  VARCHAR(100),
    date_created            TIMESTAMP DEFAULT NOW(),
    date_modified           TIMESTAMP DEFAULT NOW()
);

CREATE TRIGGER trg_permissions_touch_modified
BEFORE UPDATE ON permissions
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();


CREATE TABLE role_permissions (
    role_permission_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id            INTEGER NOT NULL,
    permission_id      INTEGER NOT NULL,
    can_view           BOOLEAN DEFAULT FALSE,
    can_create         BOOLEAN DEFAULT FALSE,
    can_update         BOOLEAN DEFAULT FALSE,
    can_delete         BOOLEAN DEFAULT FALSE,
    date_created       TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW(),
    UNIQUE (role_id, permission_id),

    CONSTRAINT fk_roles_role_permissions_role_id
      FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE,

    CONSTRAINT fk_permissions_role_permissions_permission_id
      FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
);

CREATE TRIGGER trg_role_permissions_touch_modified
BEFORE UPDATE ON role_permissions
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

CREATE INDEX index_role_permissions_role_id ON role_permissions(role_id);


CREATE TABLE users (
    user_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid           UUID UNIQUE DEFAULT gen_random_uuid(),
    first_name     VARCHAR(20),
    last_name      VARCHAR(20),
    user_email     VARCHAR(50) UNIQUE NOT NULL,
    password_hash  TEXT NOT NULL,
    role_id        INTEGER,
    last_login_at  TIMESTAMP,   -- updated on successful login
    last_seen_at   TIMESTAMP,   -- updated on every API hit or dashboard view
    last_ip        VARCHAR(64),
    is_active      BOOLEAN DEFAULT TRUE,     -- On leave or active
    company_id     INTEGER,
    date_created   TIMESTAMP DEFAULT NOW(),
    date_modified  TIMESTAMP DEFAULT NOW(),
    is_deleted     BOOLEAN DEFAULT FALSE,
    deleted_at     TIMESTAMP,

    CONSTRAINT fk_roles_users_role_id
      FOREIGN KEY (role_id) REFERENCES roles(role_id),

    CONSTRAINT fk_company_users_company_id
      FOREIGN KEY (company_id) REFERENCES company(company_id)
);

CREATE TRIGGER trg_users_touch_modified
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();


CREATE TABLE user_preferences (
    user_preference_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT UNIQUE,
    theme              VARCHAR(20) DEFAULT 'light', -- 'dark', 'light', 'system';
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


---- AUTH_SESSIONS: Stores refresh tokens, devices, and JWT identifiers
CREATE TABLE auth_sessions (
    auth_session_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id BIGINT NOT NULL,
    refresh_token_hash TEXT NOT NULL,
    jwt_id UUID NOT NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL, -- 7 - 30 days

    ip_address VARCHAR(64),
    user_agent TEXT,
    device_name VARCHAR(150),

    is_revoked BOOLEAN DEFAULT FALSE,
    revoked_at TIMESTAMP,

    replaced_by UUID,
    replaced_at TIMESTAMP,
    last_used_at TIMESTAMP,   -- updates on every refresh
    date_created TIMESTAMP DEFAULT NOW(),
    date_modified TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_auth_sessions
      FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- Logs the login times of a user
CREATE TABLE user_audit_session (
    user_audit_session_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               BIGINT,
    ip_address            VARCHAR(64),
    user_agent            TEXT,
    login_time            TIMESTAMP DEFAULT NOW(),
    logout_time           TIMESTAMP,
    session_token         VARCHAR(255),
    device_name           VARCHAR(100),
    nearest_location      VARCHAR(50),
    session_status        VARCHAR(20) DEFAULT 'active',  -- active, expired, revoked
    date_created          TIMESTAMP DEFAULT NOW(),
    date_modified         TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_user_audit_session
      FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX index_user_audit_session_user_id ON user_audit_session(user_id);
CREATE INDEX index_audit_session_login_time ON user_audit_session(login_time);


CREATE TABLE login_attempts (
    login_attempt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT,
    user_email       VARCHAR(100),
    ip_address       VARCHAR(64),
    user_agent       TEXT,
    status           VARCHAR(10) CHECK (status IN ('SUCCESS', 'FAILURE')),
    reason           TEXT,
    attempted_at     TIMESTAMP DEFAULT NOW(),
    date_created     TIMESTAMP DEFAULT NOW(),
    date_modified    TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_login_attempts
      FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

CREATE INDEX idx_login_attempts_user_email ON login_attempts(user_email);
CREATE INDEX idx_login_attempts_status ON login_attempts(status);
CREATE INDEX idx_login_attempts_ip_time ON login_attempts(ip_address, attempted_at DESC);


-- Optional per-user permissions to override role-permissions
CREATE TABLE user_permissions (
    user_permission_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT,
    permission_id      INTEGER,
    can_view           BOOLEAN,
    can_create         BOOLEAN,
    can_update         BOOLEAN,
    can_delete         BOOLEAN,
    date_created       TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_users_user_permissions
        FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,

    CONSTRAINT fk_permissions_user_permissions
        FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
);


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
  notification_channel_code        VARCHAR(50) UNIQUE NOT NULL, -- EMAIL | TELEGRAM | SMS
  notification_channel_name        VARCHAR(50),
  is_enabled                       BOOLEAN DEFAULT TRUE,
  date_created                     TIMESTAMP DEFAULT NOW(),
  date_modified                    TIMESTAMP DEFAULT NOW()
);

-- Which channel each user uses, priorities, overrides global channels
CREATE TABLE contact_group_member_channels (
    contact_group_member_channel_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contact_group_member_id         BIGINT NOT NULL,
    notification_channel_id         BIGINT NOT NULL,
    is_enabled                      BOOLEAN DEFAULT TRUE,
    priority                        SMALLINT DEFAULT 1,           -- 1 = primary, 2 = fallback
    destination_override            VARCHAR(255),                -- custom email/handle/number
    date_created                    TIMESTAMP DEFAULT NOW(),
    date_modified                   TIMESTAMP DEFAULT NOW(),
    UNIQUE (contact_group_member_id, notification_channel_id),

    CONSTRAINT fk_contact_group_members_member_channels_member_id
        FOREIGN KEY (contact_group_member_id)
            REFERENCES contact_group_members(contact_group_member_id)
            ON DELETE CASCADE,

    CONSTRAINT fk_notification_channels_member_channels_channel_id
        FOREIGN KEY (notification_channel_id)
            REFERENCES notification_channels(notification_channel_id)
            ON DELETE CASCADE
);

-- Customizable message templates
CREATE TABLE notification_templates (
    notification_template_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid                        UUID UNIQUE DEFAULT gen_random_uuid(),
    event_type                  VARCHAR(50), -- service_down, ssl_expiry...
    storage_mode                VARCHAR(20) DEFAULT 'hybrid'
                                    CHECK (storage_mode IN ('database', 'filesystem', 'hybrid')),
    subject_template            TEXT NOT NULL,
    body_template               TEXT NOT NULL,   -- Email/SMS body (HTML or plain text)
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
    status                      VARCHAR(20) DEFAULT 'sent', -- sent | failed | pending
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
  contact_group_id       BIGINT REFERENCES contact_groups(contact_group_id),
  check_interval         INTEGER DEFAULT 5,  -- minutes
  retry_count            INTEGER DEFAULT 3,
  retry_delay            INTEGER DEFAULT 5,  -- seconds
  expected_status_code   INTEGER DEFAULT 200,
  ssl_enabled            BOOLEAN DEFAULT TRUE,
  created_by             BIGINT REFERENCES users(user_id),
  date_created           TIMESTAMP DEFAULT NOW(),
  date_modified          TIMESTAMP DEFAULT NOW(),
  is_active              BOOLEAN DEFAULT TRUE
);

CREATE TRIGGER trg_monitored_services_touch_modified
BEFORE UPDATE ON monitored_services
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- High-volume logs (internal): no UUID
CREATE TABLE uptime_logs (
  uptime_log_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  monitored_service_id        BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
  status            VARCHAR(10) NOT NULL, -- UP / DOWN
  response_time_ms  INTEGER,
  http_status       INTEGER,
  error_message     TEXT,
  region            VARCHAR(100),
  checked_at        TIMESTAMP DEFAULT NOW(),
  date_created      TIMESTAMP DEFAULT NOW(),
  date_modified     TIMESTAMP DEFAULT NOW()
  CONSTRAINT fk_monitored_services_uptime_logs_monitored

);

CREATE INDEX index_uptime_logs_service_id_checked_at_status
ON uptime_logs(service_id, checked_at, status);

-- SSL logs
CREATE TABLE ssl_logs (
  ssl_log_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id      BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
  domain          VARCHAR(255),
  issuer          VARCHAR(255),
  expiry_date     DATE,
  days_remaining  INTEGER,
  last_checked    TIMESTAMP DEFAULT NOW(),
  date_created    TIMESTAMP DEFAULT NOW(),
  date_modified   TIMESTAMP DEFAULT NOW()
);

-- Incident tickets
CREATE TABLE incidents (
  incident_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uuid            UUID UNIQUE DEFAULT gen_random_uuid(),
  service_id      BIGINT REFERENCES monitored_services(monitored_service_id),
  started_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  resolved_at     TIMESTAMP,
  duration_minutes INTEGER,
  cause           TEXT,
  status          VARCHAR(20) DEFAULT 'open', -- open | resolved
  date_created    TIMESTAMP DEFAULT NOW(),
  date_modified   TIMESTAMP DEFAULT NOW()
);

CREATE TRIGGER trg_incidents_touch_modified
BEFORE UPDATE ON incidents
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Maintenance windows
/**
    -- Skip uptime checks for services covered by an active maintenance window.
    -- Suppress alerts or notifications (no downtime events).
        @service_id = 5 - Maintenance only for that specific service.
        @service_id IS NULL - Maintenance applies to all monitored services — system-wide pause.
*/
CREATE TABLE maintenance_windows (
  maintenance_window_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uuid                   UUID UNIQUE DEFAULT gen_random_uuid(),
  service_id             BIGINT NULL CONSTRAINT fk_monitored_services_maintenance_windows_service_id
                        REFERENCES monitored_services(monitored_service_id),
  start_time             TIMESTAMP NOT NULL,
  end_time               TIMESTAMP NOT NULL,
  reason                 TEXT,
  created_by             BIGINT REFERENCES users(user_id),
  date_created           TIMESTAMP DEFAULT NOW(),
  date_modified          TIMESTAMP DEFAULT NOW()
);

CREATE INDEX index_maintenance_windows_active
ON maintenance_windows(service_id, start_time, end_time);

CREATE TRIGGER trg_maintenance_windows_touch_modified
BEFORE UPDATE ON maintenance_windows
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

-- Event outbox
CREATE TABLE event_outbox (
    event_outbox_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid UUID DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100),                                 -- e.g. SERVICE_DOWN, SSL_EXPIRING
    payload         JSONB NOT NULL,                               -- the actual event data (service_id, message, etc)
    status          VARCHAR(20) DEFAULT 'PENDING',
    retries         INT DEFAULT 0,
    last_attempt_at TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_event_outbox_status ON event_outbox(status);


-- 4) REPORTING & HEALTH

CREATE TABLE uptime_summaries (
  uptime_summary_id       BIGSERIAL PRIMARY KEY,
  service_id              BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
  date                    DATE,
  uptime_percentage       NUMERIC(5,2),
  average_response_time   NUMERIC(10,2),
  incidents_count         INTEGER,
  downtime_minutes        INTEGER,
  date_created            TIMESTAMP DEFAULT NOW(),
  date_modified           TIMESTAMP DEFAULT NOW()
);

-- logs of health of system components inside SkyPulse
CREATE TABLE system_health_logs (
  system_health_log_id  BIGSERIAL PRIMARY KEY,
  component             VARCHAR(100),
  status                VARCHAR(50),
  message               TEXT,
  last_checked          TIMESTAMP DEFAULT NOW(),
  date_created          TIMESTAMP DEFAULT NOW(),
  date_modified         TIMESTAMP DEFAULT NOW()
);

-- Track execution results of each background job or cron task.
CREATE TABLE background_tasks (
    background_task_id BIGSERIAL PRIMARY KEY,
    task_name          VARCHAR(150) UNIQUE,
    task_type          VARCHAR(50),
    status             VARCHAR(20) DEFAULT 'PENDING',
    last_run_at        TIMESTAMP,
    next_run_at        TIMESTAMP,
    error_message      TEXT,
    date_created       TIMESTAMP DEFAULT NOW(),
    date_modified      TIMESTAMP DEFAULT NOW()
);

-- 5) DYNAMIC FORM DEFINITIONS (frontend renderer, manual endpoints)

CREATE TABLE form_definitions (
  form_definition_id  BIGSERIAL PRIMARY KEY,
  uuid UUID     DEFAULT gen_random_uuid(),
  form_key      VARCHAR(150) UNIQUE NOT NULL,
  title         VARCHAR(200),
  subtitle      TEXT,
  api_endpoint  VARCHAR(255),
  version       INTEGER DEFAULT 1,
  created_by    BIGINT REFERENCES users(user_id),
  date_created  TIMESTAMP DEFAULT NOW(),
  date_modified TIMESTAMP DEFAULT NOW()
);

CREATE TRIGGER trg_form_definitions_touch_modified
BEFORE UPDATE ON form_definitions
FOR EACH ROW EXECUTE FUNCTION touch_date_modified();

CREATE TABLE form_fields (
  form_field_id    BIGSERIAL PRIMARY KEY,
  form_id          BIGINT REFERENCES form_definitions(form_definition_id) ON DELETE CASCADE,
  field_key        VARCHAR(150),
  label            VARCHAR(255),
  renderer         VARCHAR(50),
  input_type       VARCHAR(50),
  default_value    TEXT,
  rules            JSONB,
  visible_when     JSONB,
  props            JSONB,
  order_index      INTEGER DEFAULT 0,
  date_created     TIMESTAMP DEFAULT NOW(),
  date_modified    TIMESTAMP DEFAULT NOW()
);

CREATE TABLE form_layouts (
  form_layout_id   BIGSERIAL PRIMARY KEY,
  form_id          BIGINT REFERENCES form_definitions(form_definition_id) ON DELETE CASCADE,
  layout           JSONB,
  order_index      INTEGER DEFAULT 0,
  date_created     TIMESTAMP DEFAULT NOW(),
  date_modified    TIMESTAMP DEFAULT NOW()
);

CREATE TABLE form_audit_log (
  form_audit_log_id  BIGSERIAL PRIMARY KEY,
  form_key           VARCHAR(150),
  submitted_by       BIGINT REFERENCES users(user_id),
  payload            JSONB,
  response           JSONB,
  submitted_at       TIMESTAMP DEFAULT NOW(),
  date_created       TIMESTAMP DEFAULT NOW(),
  date_modified      TIMESTAMP DEFAULT NOW()
);

-- 6) AUDIT & SETTINGS

CREATE TABLE audit_log (
  audit_log_id    BIGSERIAL PRIMARY KEY,
  user_id         BIGINT REFERENCES users(user_id),
  entity          VARCHAR(150),
  entity_id       BIGINT,
  action          VARCHAR(50),
  before_data     JSONB,
  after_data      JSONB,
  ip_address      VARCHAR(64),
  date_created    TIMESTAMP DEFAULT NOW(),
  date_modified   TIMESTAMP DEFAULT NOW()
);

CREATE TABLE system_settings (
  system_setting_id  BIGSERIAL PRIMARY KEY,
  key                VARCHAR(100) UNIQUE NOT NULL,
  value              TEXT,
  description        TEXT,
  date_created       TIMESTAMP DEFAULT NOW(),
  date_modified      TIMESTAMP DEFAULT NOW()
);


CREATE TABLE api_keys (
    uuid UUID     DEFAULT gen_random_uuid(),
    user_id BIGINT REFERENCES users(user_id),
    api_key_hash TEXT NOT NULL,
    scopes TEXT[],
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
