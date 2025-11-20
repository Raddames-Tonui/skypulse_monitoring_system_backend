package org.skypulse.handlers.services;


import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.security.JwtUtil;
import org.skypulse.utils.GetUserIdFromUUID;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;

public class CreateMonitoredServiceHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreateMonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        // Only POST allowed
        if (!exchange.getRequestMethod().equalToString("POST")) {
            ResponseUtil.sendError(exchange, StatusCodes.METHOD_NOT_ALLOWED, "Method not allowed");
            return;
        }

        // Extract JWT token from Authorization header
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring("Bearer ".length());
        String role = JwtUtil.getRole(token);
        String userIdStr = JwtUtil.getUserUUId(token);

        logger.info("Decoded role from JWT: {}", role);


        if (userIdStr == null || role == null || !(role.equals("ADMIN") || role.equals("OPERATOR"))) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "Insufficient permissions");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid or empty JSON body");
            return;
        }

        String name = (String) body.get("monitored_service_name");
        String url = (String) body.get("monitored_service_url");
        String region = (String) body.getOrDefault("monitored_service_region", "default");
        Integer checkInterval = (Integer) body.getOrDefault("check_interval", 5);
        Integer retryCount = (Integer) body.getOrDefault("retry_count", 3);
        Integer retryDelay = (Integer) body.getOrDefault("retry_delay", 5);
        Integer expectedStatus = (Integer) body.getOrDefault("expected_status_code", 200);
        Boolean sslEnabled = (Boolean) body.getOrDefault("ssl_enabled", true);

        if (name == null || url == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required fields: name or url");
            return;
        }

        logger.info("Creating monitored service '{}' by user {}", name, userIdStr);

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO monitored_services (
                    monitored_service_name,
                    monitored_service_url,
                    monitored_service_region,
                    check_interval,
                    retry_count,
                    retry_delay,
                    expected_status_code,
                    ssl_enabled,
                    created_by,
                    date_created,
                    date_modified
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING monitored_service_id;
            """);

            long userId = GetUserIdFromUUID.getUserIdFromUuid(userIdStr);
            Timestamp now = new Timestamp(System.currentTimeMillis());

            ps.setString(1, name);
            ps.setString(2, url);
            ps.setString(3, region);
            ps.setInt(4, checkInterval);
            ps.setInt(5, retryCount);
            ps.setInt(6, retryDelay);
            ps.setInt(7, expectedStatus);
            ps.setBoolean(8, sslEnabled);
            ps.setLong(9, userId);
            ps.setTimestamp(10, now);
            ps.setTimestamp(11, now);

            var rs = ps.executeQuery();
            if (rs.next()) {
                long serviceId = rs.getLong(1);
                ResponseUtil.sendSuccess(exchange, "Service " + name + " created successfully", null);
                logger.info("Created monitored service {} with ID {}", name, serviceId);
            } else {
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to create monitored service");
            }

        } catch (Exception e) {
            logger.error("Failed to create monitored service '{}': {}", name, e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
