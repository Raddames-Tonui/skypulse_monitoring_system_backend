package org.skypulse.config;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "configuration")
public class XmlConfiguration {

    public Server server;
    public JwtConfig jwt;
    public DataSource dataSource;
    public ConnectionPool connectionPool;
    public Logging logging;
    public Notification notification;

    // --- Undertow Server ---
    @XmlRootElement(name = "server")
    public static class Server {
        public String host;
        public int port;
        public int ioThreads;
        public int workerThreads;
        public String basePath;
    }
    @XmlRootElement(name = "jwtConfig")
    public static class JwtConfig {
        public String accessTokenTTL;
        public String refreshTokenTTL;
    }

    // --- Data Source ---
    @XmlRootElement(name = "dataSource")
    public static class DataSource {
        public String driverClassName;
        public String jdbcUrl;
        public String username;
        public String password;
        public boolean encrypt;
        public boolean trustServerCertificate;
    }

    // --- HikariCP Connection Pool ---
    @XmlRootElement(name = "connectionPool")
    public static class ConnectionPool {
        public int maximumPoolSize;
        public int minimumIdle;
        public long idleTimeout;
        public long connectionTimeout;
        public long maxLifetime;
    }

    // --- Logging ---
    @XmlRootElement(name = "logging")
    public static class Logging {
        public String level;
        public String logFile;
    }

    @XmlRootElement(name = "notification")
    public static class Notification {
        public boolean enabled;
        public Email email;
        public Telegram telegram;
        public SMS sms;

        @XmlRootElement(name = "email")
        public static class Email {
            public String smtpHost;
            public int smtpPort;
            public boolean useTLS;
            public String username;
            public String password;
            public String fromName;
            public String fromAddress;
            public int connectionTimeout;
            public int retryAttempts;
        }

        @XmlRootElement(name = "telegram")
        public static class Telegram {
            public boolean enabled;
            public String botToken;
            public String defaultChatId;
            public String apiUrl;
            public String parseMode;
            public int connectionTimeout;
        }

        @XmlRootElement(name = "sms")
        public static class SMS {
            public boolean enabled;
            public String provider;
            public String accountSid;
            public String authToken;
            public String senderNumber;
            public String defaultCountryCode;
            public String apiUrl;
            public int maxRetries;
        }
    }
}
