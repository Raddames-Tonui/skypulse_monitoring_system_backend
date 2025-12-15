-- COMPANY   -- CHANGE COMPANY NAME
INSERT INTO company (company_name, company_description)
VALUES ('SkyPulse Inc.', 'Default company for SkyPulse monitoring system')
ON CONFLICT (company_name) DO NOTHING;

-- ROLES
INSERT INTO roles (role_name, role_description)
VALUES
    ('ADMIN', 'Full system access'),
    ('OPERATOR', 'Can manage services and alerts'),
    ('VIEWER', 'Read-only dashboard access')
ON CONFLICT (role_name) DO NOTHING;

-- USER PREFERENCES
INSERT INTO user_preferences (user_id, alert_channel, language, timezone)
SELECT user_id, 'EMAIL', 'en', 'UTC' FROM users
ON CONFLICT (user_id) DO NOTHING;


-- NOTIFICATION CHANNELS
INSERT INTO notification_channels (notification_channel_code, notification_channel_name, is_enabled)
VALUES
    ('EMAIL','Email Alerts',TRUE),
    ('TELEGRAM','Telegram Bot',TRUE),
    ('SMS','Text Alerts',FALSE)
ON CONFLICT (notification_channel_code) DO NOTHING;


INSERT INTO notification_templates
(event_type, subject_template, body_template, body_template_key, storage_mode)
VALUES

(
  'SERVICE_DOWN',
  'Service Down Alert',
  '<b>Service Down Alert</b>
<b>{{service_name}}</b>

<b>Immediate Attention Required</b>

The monitored service <b>{{service_name}}</b> is currently <b>DOWN</b>.

<b>Time:</b> {{checked_at}}
<b>HTTP Code:</b> {{http_code}}
<b>Response Time:</b> {{response_time_ms}} ms

{{#error_message}}
<b>Error:</b> {{error_message}}
{{/error_message}}

Please investigate immediately.

<a href="{{dashboard_url}}">Open Dashboard</a>',
  'emails/service_down.html',
  'HYBRID'
),

(
  'SERVICE_RECOVERED',
  'Service Recovered',
  'The monitored service <b>{{service_name}}</b> has <b>RECOVERED</b>.

<b>Checked At:</b> {{checked_at}}
<b>Downtime:</b> {{downtime_seconds}} seconds
<b>HTTP Code:</b> {{http_code}}
<b>Response Time:</b> {{response_time_ms}} ms

{{#error_message}}
<b>Error:</b> {{error_message}}
{{/error_message}}

<a href="{{dashboard_url}}">View Dashboard</a>

SkyPulse Monitoring System
123 Innovation Drive, Suite 400',
  'emails/service_recovered.html',
  'HYBRID'
),

(
  'SSL_EXPIRED',
  'SSL Certificate Expiry Alert',
  'SSL certificate for <b>{{domain}}</b> (Service: <b>{{service_name}}</b>) is approaching expiry.

<b>Issuer:</b> {{issuer}}
<b>Checked at:</b> {{checked_at}}
<b>Days Remaining:</b> {{days_remaining}} days
<b>Threshold:</b> {{threshold}}%

Please renew the certificate to avoid service disruption.

<a href="{{renew_url}}">Renew SSL Now</a>',
  'emails/ssl_expiry.html',
  'HYBRID'
),

(
  'USER_CREATED',
  'User Registration',
  '',
  'emails/welcome_email.html',
  'HYBRID'
),

(
  'RESET_PASSWORD',
  'Reset your password',
  '',
  'emails/reset_password.html',
  'HYBRID'
)

ON CONFLICT DO NOTHING;


-- SYSTEM SETTINGS
INSERT INTO system_settings (
    uptime_check_interval,
    uptime_retry_count,
    uptime_retry_delay,
    sse_push_interval,

    ssl_check_interval,
    ssl_alert_thresholds,
    ssl_retry_count,
    ssl_retry_delay,

    notification_check_interval,
    notification_retry_count,
    notification_cooldown_minutes,

    version,
    is_active,
    changed_by
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
