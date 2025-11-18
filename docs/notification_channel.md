## Notification Configuration Guide

This document explains how to configure **Email**, **Telegram**, and **SMS** channels in the `config.xml` file for the SkyPulse Uptime Monitoring System.

---

### 1. Objective

A unified configuration allows the system to dynamically manage notification channels via XML or later database overrides. Each channel (Email, Telegram, SMS) can be enabled or disabled, and customized independently.

---

### 2. Example Configuration XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Undertow Server -->
    <server>
        <host>localhost</host>
        <port>8000</port>
        <ioThreads>10</ioThreads>
        <workerThreads>100</workerThreads>
        <basePath>/api/rest</basePath>
    </server>

    <!-- PostgreSQL Data Source -->
    <dataSource>
        <driverClassName>org.postgresql.Driver</driverClassName>
        <jdbcUrl>jdbc:postgresql://localhost:5434/skypulse_monitoring_system_database</jdbcUrl>
        <user>spadmin</user>
        <password>skyp@lse!2020</password>
    </dataSource>

    <!-- HikariCP -->
    <connectionPool>
        <maximumPoolSize>10</maximumPoolSize>
        <minimumIdle>2</minimumIdle>
        <idleTimeout>60000</idleTimeout>
        <connectionTimeout>30000</connectionTimeout>
        <maxLifetime>1800000</maxLifetime>
    </connectionPool>

    <!-- Logging -->
    <logging>
        <level>INFO</level>
        <logFile>logs/skypulse-db.log</logFile>
    </logging>

    <!-- ===================== Notification Channels ===================== -->
    <notifications>
        <!-- Email Configuration -->
        <email enabled="true">
            <smtpHost>smtp.gmail.com</smtpHost>
            <smtpPort>587</smtpPort>
            <useTLS>true</useTLS>
            <username>alerts@skypulse.com</username>
            <password>StrongPasswordOrToken</password>
            <fromName>SkyPulse Monitor</fromName>
            <fromAddress>alerts@skypulse.com</fromAddress>
            <connectionTimeout>10000</connectionTimeout>
            <retryAttempts>3</retryAttempts>
        </email>

        <!-- Telegram Configuration -->
        <telegram enabled="true">
            <botToken>123456789:ABCDEF-your-bot-token</botToken>
            <defaultChatId>-1001234567890</defaultChatId>
            <apiUrl>https://api.telegram.org</apiUrl>
            <parseMode>HTML</parseMode>
            <connectionTimeout>5000</connectionTimeout>
        </telegram>

        <!-- SMS Configuration (optional) -->
        <sms enabled="false">
            <provider>twilio</provider>
            <accountSid>ACxxxxxxxxxxxx</accountSid>
            <authToken>xxxxxxxxxxxx</authToken>
            <senderNumber>+12025550123</senderNumber>
            <defaultCountryCode>+254</defaultCountryCode>
            <apiUrl>https://api.twilio.com</apiUrl>
            <maxRetries>2</maxRetries>
        </sms>
    </notifications>
</configuration>
```

---

### 3. Configuration Components

#### Email Configuration

| Setting                    | Purpose                         | Example            |
| -------------------------- | ------------------------------- | ------------------ |
| `smtpHost`, `smtpPort`     | SMTP server details             | smtp.gmail.com:587 |
| `useTLS` / `useSSL`        | Encryption protocol             | true               |
| `username`, `password`     | Auth credentials                | stored encrypted   |
| `fromName` / `fromAddress` | Display name in outgoing emails | “SkyPulse Monitor” |
| `connectionTimeout`        | Timeout in ms                   | 10000              |
| `retryAttempts`            | Retry before failing            | 3                  |

#### Telegram Configuration

| Setting             | Purpose                                |
| ------------------- | -------------------------------------- |
| `botToken`          | Your Telegram bot token from BotFather |
| `defaultChatId`     | Default group/channel ID               |
| `apiUrl`            | Telegram API endpoint                  |
| `parseMode`         | Message format (Markdown / HTML)       |
| `connectionTimeout` | Request timeout in ms                  |

#### SMS Configuration

| Setting                   | Purpose                                      |
| ------------------------- | -------------------------------------------- |
| `provider`                | SMS service (Twilio, Africa’s Talking, etc.) |
| `accountSid`, `authToken` | Provider credentials                         |
| `senderNumber`            | Outgoing SMS number                          |
| `apiUrl`                  | Provider API endpoint                        |
| `maxRetries`              | Retry attempts for failures                  |

---

### 4. Security Guidelines

* Encrypt sensitive values (`password`, `authToken`, `botToken`) before saving.
* Store encryption key as environment variable.
* For production, move secrets to a secure `.env` or vault file.

---

### 5. Database Migration Option

If configuration needs to be editable via UI, mirror the XML structure in a table:

```sql
CREATE TABLE notification_settings (
    id SERIAL PRIMARY KEY,
    channel VARCHAR(20), -- email, telegram, sms
    key VARCHAR(100),
    value TEXT,
    encrypted BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMP DEFAULT NOW()
);
```

This enables runtime changes without restarting the server.

---

### 6. Runtime Integration

At startup:

```java
NotificationConfig cfg = appConfig.getNotifications();
EmailNotifier email = new EmailNotifier(cfg.getEmail());
TelegramNotifier telegram = new TelegramNotifier(cfg.getTelegram());
SmsNotifier sms = new SmsNotifier(cfg.getSms());
```

The `NotificationService` can detect which channels are enabled and send alerts in parallel.
