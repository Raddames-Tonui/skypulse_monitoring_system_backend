ALTER TABLE user_preferences
ADD CONSTRAINT chk_alert_channel
CHECK (alert_channel IN ('EMAIL', 'SMS', 'TELEGRAM'));
