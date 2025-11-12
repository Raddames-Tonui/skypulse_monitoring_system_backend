CREATE TABLE roles (
    role_id SERIAL PRIMARY KEY,
    role_name VARCHAR(20) UNIQUE NOT NULL,
    role_description TEXT
);

CREATE TABLE permissions (
    permission_id BIGSERIAL PRIMARY KEY,
    permission_code VARCHAR(20) UNIQUE NOT NULL,
    permission_description TEXT
);

CREATE TABLE role_permissions (
    role_permission_id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    can_view BOOLEAN,
    can_create BOOLEAN,
    can_update BOOLEAN,
    can_delete BOOLEAN,
    UNIQUE (role_id, permission_id),
    CONSTRAINT fk_roles_role_permissions_role_id
        FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE,
    CONSTRAINT fk_permissions_role_permissions_permission_id
        FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
);

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    uuid UUID,
    first_name VARCHAR(20),
    last_name VARCHAR(20),
    user_email VARCHAR(50) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role_id BIGINT,
    last_login_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    last_ip VARCHAR(64),
    status BOOLEAN,
    company_name VARCHAR(100),
    is_deleted BOOLEAN,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_roles_users_role_id FOREIGN KEY (role_id) REFERENCES roles(role_id)
);

CREATE TABLE user_preferences (
    user_preference_id SERIAL PRIMARY KEY,
    user_id BIGINT UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    theme VARCHAR(20),
    alert_channel VARCHAR(30),
    receive_weekly_reports BOOLEAN,
    language VARCHAR(10),
    timezone VARCHAR(100),
    dashboard_layout JSONB
);

CREATE TABLE user_contacts (
    user_contacts_id SERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    type VARCHAR(20),
    value VARCHAR(150),
    verified BOOLEAN,
    is_primary BOOLEAN
);

CREATE TABLE user_audit_session (
    user_audit_session_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    ip_address VARCHAR(64),
    user_agent TEXT,
    login_time TIMESTAMP,
    logout_time TIMESTAMP,
    session_token VARCHAR(255),
    device_name VARCHAR(100),
    nearest_location VARCHAR(50),
    session_status VARCHAR(20)
);

CREATE TABLE login_attempts (
    login_attempt_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    user_email VARCHAR(100),
    ip_address VARCHAR(64),
    user_agent TEXT,
    status VARCHAR(10),
    reason TEXT,
    attempted_at TIMESTAMP
);

CREATE TABLE user_permissions (
    user_permission_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    permission_id BIGINT REFERENCES permissions(permission_id) ON DELETE CASCADE,
    can_view BOOLEAN,
    can_create BOOLEAN,
    can_update BOOLEAN,
    can_delete BOOLEAN
);

CREATE TABLE contact_groups (
    contact_group_id SERIAL PRIMARY KEY,
    uuid UUID,
    contact_group_name VARCHAR(25) UNIQUE NOT NULL,
    contact_group_description TEXT,
    created_by BIGINT REFERENCES users(user_id),
    is_deleted BOOLEAN,
    deleted_at TIMESTAMP
);

CREATE TABLE contact_group_members (
    contact_group_member_id SERIAL PRIMARY KEY,
    contact_group_id BIGINT REFERENCES contact_groups(contact_group_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    is_primary BOOLEAN,
    added_at TIMESTAMP,
    UNIQUE (contact_group_id, user_id)
);

CREATE TABLE notification_channels (
    notification_channel_id BIGSERIAL PRIMARY KEY,
    notification_channel_code VARCHAR(50) UNIQUE NOT NULL,
    notification_channel_name VARCHAR(50),
    is_enabled BOOLEAN
);

CREATE TABLE contact_group_member_channels (
    contact_group_member_channel_id SERIAL PRIMARY KEY,
    contact_group_member_id BIGINT NOT NULL REFERENCES contact_group_members(contact_group_member_id) ON DELETE CASCADE,
    notification_channel_id BIGINT NOT NULL REFERENCES notification_channels(notification_channel_id) ON DELETE CASCADE,
    is_enabled BOOLEAN,
    priority SMALLINT,
    destination_override VARCHAR(255),
    UNIQUE (contact_group_member_id, notification_channel_id)
);

CREATE TABLE notification_templates (
    notification_template_id SERIAL PRIMARY KEY,
    uuid UUID,
    event_type VARCHAR(50),
    storage_mode VARCHAR(20),
    subject_template TEXT,
    body_template TEXT,
    pdf_template TEXT,
    include_pdf BOOLEAN,
    body_template_key VARCHAR(200),
    pdf_template_key VARCHAR(200),
    template_syntax VARCHAR(20),
    sample_data JSONB,
    created_by BIGINT REFERENCES users(user_id)
);

CREATE TABLE notification_history (
    notification_history_id BIGSERIAL PRIMARY KEY,
    service_id BIGINT,
    contact_group_id BIGINT REFERENCES contact_groups(contact_group_id),
    contact_group_member_id BIGINT REFERENCES contact_group_members(contact_group_member_id),
    notification_channel_id BIGINT REFERENCES notification_channels(notification_channel_id),
    recipient VARCHAR(255),
    subject TEXT,
    message TEXT,
    status VARCHAR(20),
    sent_at TIMESTAMP,
    error_message TEXT,
    include_pdf BOOLEAN,
    pdf_template_id BIGINT REFERENCES notification_templates(notification_template_id),
    pdf_file_path TEXT,
    pdf_file_hash VARCHAR(64),
    pdf_generated_at TIMESTAMP
);

CREATE TABLE monitored_services (
    monitored_service_id SERIAL PRIMARY KEY,
    uuid UUID,
    monitored_service_name VARCHAR(200) NOT NULL,
    monitored_service_url TEXT NOT NULL,
    monitored_service_region VARCHAR(100),
    contact_group_id BIGINT REFERENCES contact_groups(contact_group_id),
    check_interval INTEGER,
    retry_count INTEGER,
    retry_delay INTEGER,
    expected_status_code INTEGER,
    ssl_enabled BOOLEAN,
    created_by BIGINT REFERENCES users(user_id),
    is_active BOOLEAN
);

CREATE TABLE uptime_logs (
    uptime_log_id BIGSERIAL PRIMARY KEY,
    service_id BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
    status VARCHAR(10),
    response_time_ms INTEGER,
    http_status INTEGER,
    error_message TEXT,
    region VARCHAR(100),
    checked_at TIMESTAMP
);

CREATE TABLE ssl_logs (
    ssl_log_id BIGSERIAL PRIMARY KEY,
    service_id BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
    domain VARCHAR(255),
    issuer VARCHAR(255),
    expiry_date DATE,
    days_remaining INTEGER,
    last_checked TIMESTAMP
);

CREATE TABLE incidents (
    incident_id BIGSERIAL PRIMARY KEY,
    uuid UUID,
    service_id BIGINT REFERENCES monitored_services(monitored_service_id),
    started_at TIMESTAMP,
    resolved_at TIMESTAMP,
    duration_minutes INTEGER,
    cause TEXT,
    status VARCHAR(20)
);

CREATE TABLE maintenance_windows (
    maintenance_window_id BIGSERIAL PRIMARY KEY,
    uuid UUID,
    service_id BIGINT NULL REFERENCES monitored_services(monitored_service_id),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    reason TEXT,
    created_by BIGINT REFERENCES users(user_id)
);

CREATE TABLE event_outbox (
    event_outbox_id UUID PRIMARY KEY,
    event_type VARCHAR(100),
    payload JSONB,
    status VARCHAR(20),
    retries INT,
    last_attempt_at TIMESTAMP
);

CREATE TABLE uptime_summaries (
    uptime_summary_id BIGSERIAL PRIMARY KEY,
    service_id BIGINT REFERENCES monitored_services(monitored_service_id) ON DELETE CASCADE,
    date DATE,
    uptime_percentage NUMERIC(5,2),
    average_response_time NUMERIC(10,2),
    incidents_count INTEGER,
    downtime_minutes INTEGER
);

CREATE TABLE system_health_logs (
    system_health_log_id BIGSERIAL PRIMARY KEY,
    component VARCHAR(100),
    status VARCHAR(50),
    message TEXT,
    last_checked TIMESTAMP
);

CREATE TABLE background_tasks (
    background_task_id BIGSERIAL PRIMARY KEY,
    task_name VARCHAR(150),
    task_type VARCHAR(50),
    status VARCHAR(20),
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    error_message TEXT
);

CREATE TABLE form_definitions (
    form_definition_id BIGSERIAL PRIMARY KEY,
    uuid UUID,
    form_key VARCHAR(150) UNIQUE NOT NULL,
    title VARCHAR(200),
    subtitle TEXT,
    api_endpoint VARCHAR(255),
    version INTEGER,
    created_by BIGINT REFERENCES users(user_id)
);

CREATE TABLE form_fields (
    form_field_id BIGSERIAL PRIMARY KEY,
    form_id BIGINT REFERENCES form_definitions(form_definition_id) ON DELETE CASCADE,
    field_key VARCHAR(150),
    label VARCHAR(255),
    renderer VARCHAR(50),
    input_type VARCHAR(50),
    default_value TEXT,
    rules JSONB,
    visible_when JSONB,
    props JSONB,
    order_index INTEGER
);

CREATE TABLE form_layouts (
    form_layout_id BIGSERIAL PRIMARY KEY,
    form_id BIGINT REFERENCES form_definitions(form_definition_id) ON DELETE CASCADE,
    layout JSONB,
    order_index INTEGER
);

CREATE TABLE form_audit_log (
    form_audit_log_id BIGSERIAL PRIMARY KEY,
    form_key VARCHAR(150),
    submitted_by BIGINT REFERENCES users(user_id),
    payload JSONB,
    response JSONB,
    submitted_at TIMESTAMP
);

CREATE TABLE audit_log (
    audit_log_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id),
    entity VARCHAR(150),
    entity_id BIGINT,
    action VARCHAR(50),
    before_data JSONB,
    after_data JSONB,
    ip_address VARCHAR(64)
);

CREATE TABLE system_settings (
    system_setting_id BIGSERIAL PRIMARY KEY,
    key VARCHAR(100) UNIQUE NOT NULL,
    value TEXT,
    description TEXT
);
