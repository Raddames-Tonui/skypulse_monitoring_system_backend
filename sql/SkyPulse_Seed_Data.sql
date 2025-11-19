-- SkyPulse Monitoring System - CLEANED SEED DATA


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

-- PERMISSIONS
INSERT INTO permissions (permission_code, permission_description)
VALUES
 ('manage_users', 'Create, edit, and delete users'),
 ('manage_services', 'Add or remove monitored services'),
 ('view_reports', 'Access uptime and SSL reports'),
 ('configure_system', 'Modify global settings')
ON CONFLICT (permission_code) DO NOTHING;

-- USERS
INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, company_id)
SELECT 'Alice','Muthoni','admin@skywatch.com',
       '$2a$12$1234567890523456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r WHERE r.role_name='Admin'
UNION ALL
SELECT 'Brian','Otieno','ops@skywatch.com',
       '$2a$12$1234567890153456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r WHERE r.role_name='Operator'
UNION ALL
SELECT 'Carol','Wanjiku','viewer@skywatch.com',
       '$2a$12$1234567890823456789012abcdefghijklmno12345678901234567890',
       r.role_id, 1
FROM roles r WHERE r.role_name='Viewer'
ON CONFLICT (user_email) DO NOTHING;

-- USER PREFERENCES
INSERT INTO user_preferences (user_id, theme, alert_channel, language, timezone)
SELECT user_id, 'dark', 'email', 'en', 'UTC' FROM users
ON CONFLICT (user_id) DO NOTHING;

-- USER CONTACTS
INSERT INTO user_contacts (user_id, type, value, verified, is_primary)
SELECT user_id, 'email', user_email, TRUE, TRUE FROM users
ON CONFLICT DO NOTHING;

-- LOGIN ATTEMPTS
INSERT INTO login_attempts (user_id, user_email, ip_address, status, reason)
SELECT user_id, user_email, '102.45.66.7', 'SUCCESS', NULL
FROM users WHERE user_email='admin@skywatch.com'
UNION ALL
SELECT user_id, user_email, '197.231.15.10', 'FAILURE', 'Incorrect password'
FROM users WHERE user_email='ops@skywatch.com';

-- USER AUDIT SESSION
INSERT INTO user_audit_session (user_id, ip_address, user_agent, login_time)
SELECT user_id, '102.45.66.7', 'Chrome/120.0', NOW() - INTERVAL '3 hours'
FROM users WHERE user_email='admin@skywatch.com';

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

-- MONITORED SERVICES
INSERT INTO monitored_services (monitored_service_name, monitored_service_url, monitored_service_region,
                                check_interval, retry_count, ssl_enabled, created_by)
SELECT 'Main Website','https://skywatch.com','default', 5, 3, TRUE, u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
UNION ALL
SELECT 'API Gateway','https://api.skywatch.com/health','default', 2, 3, TRUE, u.user_id
FROM users u WHERE u.user_email='ops@skywatch.com'
UNION ALL
SELECT 'Client Portal','https://portal.skywatch.com','default', 10, 2, TRUE, u.user_id
FROM users u WHERE u.user_email='admin@skywatch.com'
ON CONFLICT DO NOTHING;

-- LINK SERVICES TO CONTACT GROUPS (many-to-many)
INSERT INTO monitored_services_contact_groups (monitored_service_id, contact_group_id)
SELECT s.monitored_service_id, cg.contact_group_id
FROM monitored_services s
JOIN contact_groups cg ON cg.contact_group_name='Ops Team'
WHERE s.monitored_service_name IN ('Main Website','API Gateway')
ON CONFLICT DO NOTHING;

INSERT INTO monitored_services_contact_groups (monitored_service_id, contact_group_id)
SELECT s.monitored_service_id, cg.contact_group_id
FROM monitored_services s
JOIN contact_groups cg ON cg.contact_group_name='Developers'
WHERE s.monitored_service_name='Client Portal'
ON CONFLICT DO NOTHING;

-- UPTIME LOGS
INSERT INTO uptime_logs (monitored_service_id, status, response_time_ms, http_status, region)
SELECT monitored_service_id, 'UP', (100 + random()*400)::int, 200, 'default'
FROM monitored_services
LIMIT 5;

-- SSL LOGS
INSERT INTO ssl_logs (monitored_service_id, domain, issuer, expiry_date, days_remaining)
SELECT monitored_service_id, monitored_service_url, 'Let''s Encrypt',
       NOW() + INTERVAL '60 days', 60
FROM monitored_services
WHERE ssl_enabled=TRUE;

-- INCIDENTS
INSERT INTO incidents (monitored_service_id, cause, started_at, resolved_at, status)
SELECT monitored_service_id, 'Server downtime', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours', 'resolved'
FROM monitored_services WHERE monitored_service_name='Main Website'
UNION ALL
SELECT monitored_service_id, 'Slow API response', NOW() - INTERVAL '1 day', NULL, 'open'
FROM monitored_services WHERE monitored_service_name='API Gateway';

-- MAINTENANCE WINDOWS
INSERT INTO maintenance_windows (monitored_service_id, start_time, end_time, reason, created_by)
SELECT monitored_service_id, NOW()+INTERVAL '1 day', NOW()+INTERVAL '1 day 1 hour', 'System Upgrade', u.user_id
FROM monitored_services, users u WHERE u.user_email='admin@skywatch.com';

-- SYSTEM HEALTH LOGS
INSERT INTO system_health_logs (component, status, message)
VALUES
 ('uptime_worker','OK','50 services checked'),
 ('ssl_checker','OK','SSL checks completed'),
 ('notifier','DEGRADED','Telegram latency'),
 ('report_generator','OK','Daily reports generated');

-- BACKGROUND TASKS
INSERT INTO background_tasks (task_name, task_type, status, last_run_at, next_run_at)
VALUES
 ('Uptime Worker','uptime_check','SUCCESS',NOW()-INTERVAL '5 minutes',NOW()+INTERVAL '5 minutes'),
 ('SSL Monitor','ssl_check','SUCCESS',NOW()-INTERVAL '1 hour',NOW()+INTERVAL '1 hour'),
 ('Report Generator','report','SUCCESS',NOW()-INTERVAL '2 hours',NOW()+INTERVAL '12 hours');

-- NOTIFICATION TEMPLATES
INSERT INTO notification_templates (event_type, subject_template, body_template)
VALUES
 ('service_down','Service Down: {{service_name}}','Service {{service_name}} is DOWN at {{timestamp}}'),
 ('service_up','Service Up: {{service_name}}','Service {{service_name}} recovered at {{timestamp}}'),
 ('ssl_expiry','SSL Expiry Warning: {{domain}}','SSL for {{domain}} expires in {{days_remaining}} days.')
ON CONFLICT DO NOTHING;

-- SYSTEM SETTINGS
INSERT INTO system_settings (key, value, description)
VALUES
 ('default_check_interval','5','Default uptime check interval in minutes'),
 ('ssl_expiry_threshold_days','30','Days before SSL expiry to alert'),
 ('retry_count','3','Number of retries before marking service as down'),
 ('timezone','UTC','System default timezone')
ON CONFLICT (key) DO NOTHING;

-- FORM DEFINITIONS
INSERT INTO form_definitions (form_key, title, subtitle, api_endpoint)
VALUES
 ('service_registration','Register New Service','Add new monitored endpoint','/api/rest/create-service'),
 ('contact_group_form','Create Contact Group','Define alert recipients','/api/rest/create-contact-group')
ON CONFLICT (form_key) DO NOTHING;

-- AUDIT LOG
INSERT INTO audit_log (user_id, entity, entity_id, action, ip_address, after_data)
SELECT u.user_id, 'monitored_services', s.monitored_service_id, 'CREATE', '127.0.0.1',
       json_build_object('name', s.monitored_service_name)
FROM users u, monitored_services s
WHERE u.user_email='admin@skywatch.com'
LIMIT 3;
