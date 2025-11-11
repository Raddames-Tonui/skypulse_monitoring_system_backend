-- ===================================================================
-- SEED DATA FOR SYSTEM INITIALIZATION
-- ===================================================================

-- 1) ROLES
INSERT INTO roles (name, description)
VALUES
('Admin', 'Full system access'),
('Operator', 'Manages uptime and notifications'),
('Viewer', 'Can only view dashboards');

-- 2) PERMISSIONS
INSERT INTO permissions (code, description)
VALUES
('manage_users', 'Create, edit, or deactivate users'),
('view_reports', 'Access analytics and uptime reports'),
('manage_services', 'Add or configure monitored services'),
('view_services', 'View monitored service status'),
('manage_notifications', 'Edit notification templates and contact groups');

-- 3) ROLE-PERMISSION MAPPINGS
INSERT INTO role_permissions (role_id, permission_id, can_view, can_create, can_update, can_delete)
SELECT
  r.id, p.id, TRUE, TRUE, TRUE, TRUE
FROM roles r
JOIN permissions p ON r.name = 'Admin';

INSERT INTO role_permissions (role_id, permission_id, can_view, can_create, can_update, can_delete)
SELECT
  r.id, p.id, TRUE, TRUE, TRUE, FALSE
FROM roles r
JOIN permissions p ON r.name = 'Operator';

INSERT INTO role_permissions (role_id, permission_id, can_view, can_create, can_update, can_delete)
SELECT
  r.id, p.id, TRUE, FALSE, FALSE, FALSE
FROM roles r
JOIN permissions p ON r.name = 'Viewer';

-- 4) ADMIN USER
INSERT INTO users (uuid, first_name, last_name, email, password_hash, role_id, is_active, timezone)
VALUES (
  uuid_generate_v4(),
  'System',
  'Administrator',
  'admin@system.local',
  '$2a$12$XEXAMPLEHASHQp3ZbG6z9x1Gf2kjwOC8fQwY7pPoXG', -- Replace with real bcrypt hash
  (SELECT id FROM roles WHERE name='Admin'),
  TRUE,
  'Africa/Nairobi'
);

-- 5) CONTACT GROUPS
INSERT INTO contact_groups (uuid, name, description, created_by)
VALUES
(uuid_generate_v4(), 'Default Alerts', 'Primary alert recipients', 1),
(uuid_generate_v4(), 'Technical Team', 'Developers and SREs', 1);

INSERT INTO contact_group_members (group_id, name, email, phone)
VALUES
((SELECT id FROM contact_groups WHERE name='Default Alerts'), 'Admin User', 'admin@system.local', '254700000001'),
((SELECT id FROM contact_groups WHERE name='Technical Team'), 'DevOps', 'ops@system.local', '254700000002');

-- 6) NOTIFICATION CHANNELS
INSERT INTO notification_channels (code, name)
VALUES
('EMAIL', 'Email Notifications'),
('SMS', 'SMS Alerts'),
('TELEGRAM', 'Telegram Alerts');

-- 7) MONITORED SERVICE
INSERT INTO monitored_services (uuid, name, url, region, contact_group_id, created_by)
VALUES (
  uuid_generate_v4(),
  'Main Website',
  'https://example.com',
  'global',
  (SELECT id FROM contact_groups WHERE name='Default Alerts'),
  1
);

-- 8) SAMPLE SSL LOG
INSERT INTO ssl_logs (service_id, domain, issuer, expiry_date, days_remaining)
VALUES (
  (SELECT id FROM monitored_services WHERE name='Main Website'),
  'example.com',
  'Let''s Encrypt',
  CURRENT_DATE + INTERVAL '75 days',
  75
);

-- 9) SAMPLE UPTIME LOGS
INSERT INTO uptime_logs (service_id, status, response_time_ms, http_status, checked_at)
VALUES
((SELECT id FROM monitored_services WHERE name='Main Website'), 'UP', 320, 200, NOW() - INTERVAL '5 minutes'),
((SELECT id FROM monitored_services WHERE name='Main Website'), 'UP', 410, 200, NOW() - INTERVAL '10 minutes'),
((SELECT id FROM monitored_services WHERE name='Main Website'), 'DOWN', NULL, 500, NOW() - INTERVAL '20 minutes');

-- 10) SAMPLE INCIDENT
INSERT INTO incidents (uuid, service_id, started_at, cause, status)
VALUES (
  uuid_generate_v4(),
  (SELECT id FROM monitored_services WHERE name='Main Website'),
  NOW() - INTERVAL '20 minutes',
  'HTTP 500 responses detected',
  'open'
);

-- 11) MAINTENANCE WINDOW
INSERT INTO maintenance_windows (uuid, service_id, start_time, end_time, reason, created_by)
VALUES (
  uuid_generate_v4(),
  (SELECT id FROM monitored_services WHERE name='Main Website'),
  NOW() + INTERVAL '1 day',
  NOW() + INTERVAL '1 day 2 hours',
  'Scheduled deployment window',
  1
);

-- 12) NOTIFICATION TEMPLATE
INSERT INTO notification_templates (uuid, event_type, subject_template, body_template, created_by)
VALUES (
  uuid_generate_v4(),
  'service_down',
  'Service {{service_name}} is DOWN',
  'Alert: The service {{service_name}} failed at {{timestamp}}. Status: {{status}}.',
  1
);

-- 13) FORM DEFINITIONS
INSERT INTO form_definitions (uuid, form_key, title, subtitle, api_endpoint, created_by)
VALUES (
  uuid_generate_v4(),
  'user-registration',
  'Create User Account',
  'Admin form for registering users',
  '/api/rest/create-user',
  1
);

INSERT INTO form_fields (form_id, field_key, label, renderer, input_type, rules, order_index)
VALUES
(
  (SELECT id FROM form_definitions WHERE form_key='user-registration'),
  'first_name',
  'First Name',
  'text',
  'text',
  '{"required": "First name is required"}',
  1
),
(
  (SELECT id FROM form_definitions WHERE form_key='user-registration'),
  'email',
  'Email',
  'text',
  'email',
  '{"required": "Email is required"}',
  2
),
(
  (SELECT id FROM form_definitions WHERE form_key='user-registration'),
  'password',
  'Password',
  'text',
  'password',
  '{"required": "Password is required"}',
  3
);

INSERT INTO form_layouts (form_id, layout)
VALUES
(
  (SELECT id FROM form_definitions WHERE form_key='user-registration'),
  '{"kind": "stack", "children": [{"kind": "field", "fieldId": "first_name"}, {"kind": "field", "fieldId": "email"}, {"kind": "field", "fieldId": "password"}]}'
);

-- 14) SYSTEM SETTINGS
INSERT INTO system_settings (key, value, description)
VALUES
('system.name', 'PulseWatch', 'System display name'),
('check.interval.default', '5', 'Default service check interval (minutes)'),
('alert.retries', '3', 'Number of retries before marking as down'),
('timezone.default', 'Africa/Nairobi', 'System timezone');
