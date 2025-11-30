-- COMPANY
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


-- SYSTEM SETTINGS
INSERT INTO system_settings (key, description, uptime_check_interval, uptime_retry_count, uptime_retry_delay, ssl_check_interval, ssl_alert_thresholds, notification_retry_count)
VALUES
('uptime_check_interval','Interval in minutes between uptime checks',5,3,5,360,'30,14,7',5),
('uptime_retry_count','Number of retries before marking service DOWN',5,3,5,360,'30,14,7',5),
('uptime_retry_delay','Delay in seconds between retries',5,3,5,360,'30,14,7',5),
('ssl_check_interval','Interval in minutes between SSL checks',5,3,5,360,'30,14,7',5),
('ssl_alert_thresholds','Days before SSL expiry to alert',5,3,5,360,'30,14,7',5),
('notification_retry_count','Retry attempts for failed notifications',5,3,5,360,'30,14,7',5)
ON CONFLICT (key) DO NOTHING;
