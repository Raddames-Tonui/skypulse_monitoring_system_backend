-- ============================================================
-- SKY PULSE MONITORING SYSTEM • PRODUCTION SEED DATA
-- Fully compatible with current schema
-- Primary admin: 
-- ============================================================

-- COMPANY   -- CHANGE COMPANY NAME
INSERT INTO company (company_name, company_description)
VALUES ('SkyPulse Inc.', 'Default company for SkyPulse monitoring system')
ON CONFLICT (company_name) DO NOTHING;

-- ROLES
INSERT INTO roles (role_name, role_description)
VALUES
    ('Admin', 'Full system access'),
    ('Operator', 'Can manage services and alerts'),
    ('Viewer', 'Read-only dashboard access')
ON CONFLICT (role_name) DO NOTHING;

-- USER PREFERENCES
INSERT INTO user_preferences (user_id, alert_channel, language, timezone)
SELECT user_id, 'email', 'en', 'UTC' FROM users
ON CONFLICT (user_id) DO NOTHING;


-- NOTIFICATION CHANNELS
INSERT INTO notification_channels (notification_channel_code, notification_channel_name, is_enabled)
VALUES
    ('EMAIL','Email Alerts',TRUE),
    ('TELEGRAM','Telegram Bot',TRUE),
    ('SMS','Text Alerts',FALSE)
ON CONFLICT (notification_channel_code) DO NOTHING;


INSERT INTO notification_templates (event_type, subject_template, body_template, body_template_key, storage_mode)
VALUES
('SERVICE_DOWN','Service Down - {{service_name}}','<p>Service {{service_name}} is down.</p>','emails/service_down.html','hybrid'),
('SERVICE_RECOVERED','Service Recovered - {{service_name}}','<p>Service {{service_name}} has recovered.</p>','emails/service_recovered.html','hybrid')
('SSL_EXPIRED','SSL Expiry Warning:  - {{domain}}','<p>Service {{service_name}} has recovered.</p>','emails/ssl_expiry.html','hybrid')
('USER_CREATED','User Registration','<p>Service {{service_name}} has recovered.</p>','emails/welcome_email.html','hybrid')
('RESET_PASSWORD','Reset your password for - {{brand_name}}','<p>Service {{service_name}} has recovered.</p>','emails/reset_password.html','hybrid')
ON CONFLICT DO NOTHING;



-- SYSTEM SETTINGS
INSERT INTO system_settings (   uptime_check_interval, uptime_retry_count,  uptime_retry_delay,   sse_push_interval,
    ssl_check_interval, ssl_alert_thresholds,    ssl_retry_count,    ssl_retry_delay,
    notification_check_interval,    notification_retry_count,   notification_cooldown_minutes,
    version,    is_active,  changed_by
)
VALUES
(
    5,       -- uptime_check_interval (5 seconds)
    3,         -- uptime_retry_count
    5,         -- uptime_retry_delay (seconds)
    5,         -- sse_push_interval (seconds)

    360,       -- ssl_check_interval (seconds)
    '30,14,7,3', -- ssl_alert_thresholds
    3,         -- ssl_retry_count
    60,       -- ssl_retry_delay

    5,       -- notification_check_interval (5 sec)
    5,         -- notification_retry_count
    3,        -- notification_cooldown_minutes

    1,         -- version
    TRUE,      -- is_active
    NULL       -- changed_by (no user yet)
);
