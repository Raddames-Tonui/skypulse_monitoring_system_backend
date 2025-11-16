package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.Headers;
import org.skypulse.config.XmlConfiguration;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.security.KeyProvider;
import org.skypulse.config.base.CORSHander;
import org.skypulse.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class RestApiServer {
    private static final Logger logger = LoggerFactory.getLogger(RestApiServer.class);
    private static final Instant START_TIME = Instant.now();

    public static void startUndertow(XmlConfiguration cfg) {
        if (cfg == null || cfg.server == null) {
            logger.error("Invalid configuration: missing server configuration.");
            throw new IllegalArgumentException("Invalid configuration: missing server section.");
        }
        try {
            String BASE_REST_API_URL = cfg.server.basePath;

            // --- health-check endpoint ---
            HttpHandler rootHandler = exchange -> {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("app", "SkyPulse REST API");
                status.put("version", "1.0.0");
                status.put("environment", KeyProvider.getEnvironment());
                status.put("uptime_seconds", Duration.between(START_TIME, Instant.now()).toSeconds());
                status.put("timestamp", Instant.now().toString());

                // DB connection health
                boolean dbOK = false;
                if (DatabaseManager.isInitialized()) {
                    try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
                        dbOK = conn.isValid(2);
                    } catch (SQLException ignored) {
                    }
                }
                status.put("database", dbOK ? "connected" : "unavailable");

                String json = JsonUtil.mapper().writeValueAsString(status);
                exchange.getResponseSender().send(json);

            };

            PathHandler pathHandler = Handlers.path()
                    .addPrefixPath(BASE_REST_API_URL+"/health", rootHandler)
                    .addPrefixPath(BASE_REST_API_URL + "/auth",  Routes.auth());


            Undertow server = Undertow.builder()
                    .setServerOption(UndertowOptions.DECODE_URL, true)
                    .setServerOption(UndertowOptions.URL_CHARSET, StandardCharsets.UTF_8.name())
//                    .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
//                    .setServerOption(UndertowOptions.ALLOW_ENCODED_SLASH, true)
                    .setIoThreads(cfg.server.ioThreads)
                    .setWorkerThreads(cfg.server.workerThreads)
                    .addHttpListener(cfg.server.port, cfg.server.host)
                    .setHandler(new CORSHander(pathHandler))
                    .build();

            server.start();
            logger.info("""
                                              \s
                                                   .---.           .---.
                                                  /     \\\\\\\\  __   //     \\\\\\\\
                                                 / /     \\\\\\\\(o o)//     \\\\ \\\\\\\\
                                                //////   '\\\\\\\\ ^ //'      \\\\\\\\\\\\\\\\
                                               //// / // :     :   \\\\\\\\  \\\\ \\\\\\\\\\\\\\\\
                                              // /   /  /`----'\\\\      \\\\   \\\\ \\\\\\\\
                                                        \\\\\\\\..////
                                        =================UU====UU====================
                                                         '//||||\\\\\\\\`
                                                           ''''
                                        SKYPULSE MONITORING REST API
                                        --------------------------------------
                                        Undertow server started successfully!
                                        Database connection established.
                                        Host   : http://{}:{}{}
                   \s""",
                    cfg.server.host, cfg.server.port, cfg.server.basePath);

        } catch (Exception e){
            logger.error("Error starting server: {}", e.getMessage());
            System.exit(-1);
        }
    }

}
