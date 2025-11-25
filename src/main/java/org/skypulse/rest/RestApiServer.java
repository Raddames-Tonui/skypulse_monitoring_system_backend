package org.skypulse.rest;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.PathHandler;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.handlers.SseHealthCheckHandler;
import org.skypulse.rest.base.CORSHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

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

            SseHealthCheckHandler sseHealthCheckHandler = new SseHealthCheckHandler();

            PathHandler pathHandler = Handlers.path()
                    .addPrefixPath(BASE_REST_API_URL + "/system", Routes.health(cfg))
                    .addPrefixPath(BASE_REST_API_URL + "/auth",  Routes.auth(cfg))
                    .addPrefixPath(BASE_REST_API_URL + "/contacts",  Routes.contactGroups(cfg))
                    .addPrefixPath(BASE_REST_API_URL + "/services", Routes.monitoredServices(cfg))
                    .addPrefixPath(BASE_REST_API_URL+"/settings", Routes.systemSettings(cfg))

                    .addPrefixPath(BASE_REST_API_URL + "/system/health/stream", sseHealthCheckHandler.getHandler());



            Undertow server = Undertow.builder()
                    .setServerOption(UndertowOptions.DECODE_URL, true)
                    .setServerOption(UndertowOptions.URL_CHARSET, StandardCharsets.UTF_8.name())
//                    .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
//                    .setServerOption(UndertowOptions.ALLOW_ENCODED_SLASH, true)
                    .setIoThreads(cfg.server.ioThreads)
                    .setWorkerThreads(cfg.server.workerThreads)
                    .addHttpListener(cfg.server.port, cfg.server.host)
                    .setHandler(new CORSHandler(pathHandler, "http://localhost:5173"))
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
                                        Host   : http://{}:{}{}
                   \s""",
                    cfg.server.host, cfg.server.port, cfg.server.basePath);

        } catch (Exception e){
            logger.error("Error starting server: {}", e.getMessage());
            System.exit(-1);
        }
    }

}
