package org.skypulse.config;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "configuration")
public class Configuration {

    public Server server;
    public DataSource dataSource;
    public ConnectionPool connectionPool;

    @XmlRootElement(name = "Server")
    public static class Server {
        public String host;
        public int port;
        public int ioThreads;
        public int workerThreads;
        public String basePath;
    }

    @XmlRootElement(name = "dataSource")
    public static class DataSource {
        public String driverClassName;
        public String jdbcUrl;
        public String user;
        public String password;
        public boolean encrypt;
        public boolean trustServerCertificate;
    }

    @XmlRootElement(name = "connectionPool")
    public static class ConnectionPool {
        public int maximumPoolSize;
        public int minimumIdle;
        public int idleTimeout;
        public long connectionTimeout;
        public long maxLifetime;
    }
}
