-- ============================================================
-- SKY PULSE MONITORING SYSTEM • CLEAN SEED DATA (MERGED v4)
-- Compatible with new schema (auth_sessions + login_failures)
-- Includes primary admin: raddamestonui48@gmail.com
-- ============================================================

-- COMPANY
INSERT INTO company (company_name, company_description)
VALUES ('SkyWatch Inc.', 'Default company for SkyPulse monitoring system')
ON CONFLICT (company_name) DO NOTHING;

-- ROLES
INSERT INTO roles (role_name, role_description)
VALUES
    ('Admin', 'Full system access'),
    ('Operator', 'Can manage services and alerts'),
    ('Viewer', 'Read-only dashboard access')
ON CONFLICT (role_name) DO NOTHING;

-- USERS
-- USERS
INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, company_id)
SELECT 'Alice','Muthoni','admin@skywatch.com',
       '$2a$12$1234567890523456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r
WHERE r.role_name = 'Admin'
ON CONFLICT (user_email) DO NOTHING;

INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, company_id)
SELECT 'Brian','Otieno','ops@skywatch.com',
       '$2a$12$1234567890153456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r
WHERE r.role_name = 'Operator'
ON CONFLICT (user_email) DO NOTHING;

INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, company_id)
SELECT 'Carol','Wanjiku','viewer@skywatch.com',
       '$2a$12$1234567890823456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r
WHERE r.role_name = 'Viewer'
ON CONFLICT (user_email) DO NOTHING;

INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, company_id)
SELECT 'John','Dowe','radda@gmail.com',
       '$2a$12$ABCDEF1234567890abcdefghijklmnopqrstuv0987654321zzzzzzzz',
       r.role_id, 1
FROM roles r
WHERE r.role_name = 'Admin'
ON CONFLICT (user_email) DO NOTHING;


-- USER PREFERENCES
INSERT INTO user_preferences (user_id, alert_channel, language, timezone)
SELECT user_id, 'email', 'en', 'UTC' FROM users
ON CONFLICT (user_id) DO NOTHING;

-- USER CONTACTS
INSERT INTO user_contacts (user_id, type, value, verified, is_primary)
SELECT user_id, 'email', user_email, TRUE, TRUE FROM users
ON CONFLICT DO NOTHING;

-- LOGIN FAILURES (failed logins)
INSERT INTO login_failures (user_id, user_email, ip_address, user_agent, reason)
SELECT user_id, user_email, '197.231.15.10', 'Chrome/120.0', 'Incorrect password'
FROM users WHERE user_email='ops@skywatch.com'
ON CONFLICT DO NOTHING;

-- AUTH SESSIONS (successful logins)
INSERT INTO auth_sessions (user_id, refresh_token_hash, jwt_id, issued_at, expires_at, last_used_at, login_time, ip_address, user_agent, device_name, session_status)
SELECT user_id,
       'placeholder_refresh_hash',
       uuid_generate_v4(),
       NOW() - INTERVAL '3 hours',
       NOW() + INTERVAL '30 days',
       NOW() - INTERVAL '3 hours',
       NOW() - INTERVAL '3 hours',
       '102.45.66.7',
       'Chrome/120.0',
       'Default Device',
       'active'
FROM users WHERE user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;

-- NOTIFICATION CHANNELS
INSERT INTO notification_channels (notification_channel_code, notification_channel_name, is_enabled)
VALUES
    ('EMAIL','Email Alerts',TRUE),
    ('TELEGRAM','Telegram Bot',TRUE),
    ('SMS','Text Alerts',FALSE)
ON CONFLICT (notification_channel_code) DO NOTHING;

-- CONTACT GROUPS
INSERT INTO contact_groups (contact_group_name, contact_group_description, created_by)
SELECT 'Ops Team','Handles uptime and SSL issues', u.user_id
FROM users u WHERE u.user_email='ops@skywatch.com'
ON CONFLICT (contact_group_name) DO NOTHING;

INSERT INTO contact_groups (contact_group_name, contact_group_description, created_by)
SELECT 'Developers','Investigates incidents and bug reports', u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT (contact_group_name) DO NOTHING;

INSERT INTO contact_groups (contact_group_name, contact_group_description, created_by)
SELECT 'Executives','Receives summary reports', u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT (contact_group_name) DO NOTHING;

-- CONTACT GROUP MEMBERS
INSERT INTO contact_group_members (contact_group_id, user_id, is_primary)
SELECT cg.contact_group_id, u.user_id, TRUE
FROM contact_groups cg
JOIN users u ON u.user_email IN ('admin@skywatch.com','raddamestonui48@gmail.com')
WHERE cg.contact_group_name='Executives'
ON CONFLICT DO NOTHING;

-- MONITORED SERVICES
INSERT INTO monitored_services (monitored_service_name, monitored_service_url, monitored_service_region, check_interval, retry_count, ssl_enabled, created_by)
SELECT 'Main Website','https://skywatch.com','default', 5, 3, TRUE, u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
UNION ALL
SELECT 'Trainee DOJO','https://the-dojo.pagoda.africa/api','default', 2, 3, TRUE, u.user_id
FROM users u WHERE u.user_email='ops@skywatch.com'
UNION ALL
SELECT 'To do Website','https://taddaaaaaa.netlify.app','default', 10, 2, TRUE, u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;

-- MONITORED SERVICES – CONTACT GROUP LINK
INSERT INTO monitored_services_contact_groups (monitored_service_id, contact_group_id)
SELECT s.monitored_service_id, cg.contact_group_id
FROM monitored_services s
JOIN contact_groups cg ON cg.contact_group_name='Ops Team'
WHERE s.monitored_service_name='Main Website'
ON CONFLICT DO NOTHING;

-- SYSTEM SETTINGS (key/value)
INSERT INTO system_settings (uptime_check_interval, uptime_retry_count, uptime_retry_delay, ssl_check_interval, ssl_alert_thresholds, notification_retry_count, key, value, description)
VALUES
    (5,3,5,360,'30,14,7,3',5,'uptime_check_interval','5','Interval in minutes between uptime checks'),
    (5,3,5,360,'30,14,7,3',5,'uptime_retry_count','3','Number of retries before marking service DOWN'),
    (5,3,5,360,'30,14,7,3',5,'uptime_retry_delay','5','Delay in seconds between retries'),
    (5,3,5,360,'30,14,7,3',5,'ssl_check_interval','360','Interval in minutes between SSL checks'),
    (5,3,5,360,'30,14,7,3',5,'ssl_alert_thresholds','30,14,7,3','Days before SSL expiry to alert'),
    (5,3,5,360,'30,14,7,3',5,'notification_retry_count','5','Retry attempts for failed notifications')
ON CONFLICT (key) DO NOTHING;

-- NOTIFICATION TEMPLATES
INSERT INTO notification_templates (event_type, subject_template, body_template, created_by)
SELECT 'service_down','Service Down: {{service_name}}','Service {{service_name}} is DOWN at {{timestamp}}', u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;

INSERT INTO notification_templates (event_type, subject_template, body_template, created_by)
SELECT 'service_up','Service Up: {{service_name}}','Service {{service_name}} recovered at {{timestamp}}', u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;

INSERT INTO notification_templates (event_type, subject_template, body_template, created_by)
SELECT 'ssl_expiry','SSL Expiry Warning: {{domain}}','SSL for {{domain}} expires in {{days_remaining}} days.', u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;





INSERT INTO notification_templates
(event_type, subject_template, body_template, body_template_key, storage_mode)
VALUES
(
    'SERVICE_DOWN',
    'Service Down Alert - {{service_name}}',
    '<p>Service {{service_name}} is down.</p>', -- simple fallback if filesystem fails
    'service_down.html',                        -- file name in templates folder
    'hybrid'
);

-- SERVICE_RECOVERED template
INSERT INTO notification_templates
(event_type, subject_template, body_template, body_template_key, storage_mode)
VALUES
(
    'SERVICE_RECOVERED',
    'Service Recovered - {{service_name}}',
    '<p>Service {{service_name}} has recovered.</p>', -- fallback HTML
    'service_recovered.html',
    'hybrid'
);