package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.security.JwtUtil;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;

public class MonitoredServiceHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(MonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        if (!"ADMIN".equalsIgnoreCase(ctx.getRoleName()) && !"OPERATOR".equalsIgnoreCase(ctx.getRoleName())) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "User not authorized");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid or empty JSON body");
            return;
        }

        Long serviceId = HttpRequestUtil.getLong(body, "monitored_service_id");
        String name = HttpRequestUtil.getString(body, "monitored_service_name");
        String url = HttpRequestUtil.getString(body, "monitored_service_url");

        if (name == null || url == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required fields: name or url");
            return;
        }

        String region = HttpRequestUtil.getString(body,"monitored_service_region");
        Integer checkInterval = HttpRequestUtil.getInteger(body,"check_interval");
        Integer retryCount = HttpRequestUtil.getInteger(body,"retry_count");
        Integer retryDelay = HttpRequestUtil.getInteger(body,"retry_delay");
        Integer expectedStatus = HttpRequestUtil.getInteger(body,"expected_status_code");
        Boolean sslEnabled = (Boolean) body.getOrDefault("ssl_enabled", true);

        long userId = JwtUtil.getUserIdFromUuid(ctx.getUuid().toString());
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            if (serviceId == null) {

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

                monitoredServiceDto(name, url, region, checkInterval, retryCount, retryDelay, expectedStatus, ps);
                ps.setBoolean(8, sslEnabled);
                ps.setLong(9, userId);
                ps.setTimestamp(10, now);
                ps.setTimestamp(11, now);

                var rs = ps.executeQuery();
                if (!rs.next()) {
                    ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to create monitored service");
                    return;
                }

                long newId = rs.getLong(1);

                AuditLogger.log(exchange, "monitored_services", newId, "CREATE", null,
                        HttpRequestUtil.toJsonString(body));

                ResponseUtil.sendSuccess(exchange, "Monitored service created", Map.of("monitored_service_id", newId));
                return;
            }


            // Load before_data
            PreparedStatement getOld = conn.prepareStatement(
                    "SELECT row_to_json(t) AS data FROM (SELECT * FROM monitored_services WHERE monitored_service_id = ?) t"
            );
            getOld.setLong(1, serviceId);
            var rsOld = getOld.executeQuery();
            String beforeData = rsOld.next() ? rsOld.getString("data") : null;

            PreparedStatement psUpdate = conn.prepareStatement("""
                UPDATE monitored_services SET
                    monitored_service_name = COALESCE(?, monitored_service_name),
                    monitored_service_url = COALESCE(?, monitored_service_url),
                    monitored_service_region = COALESCE(?, monitored_service_region),
                    check_interval = COALESCE(?, check_interval),
                    retry_count = COALESCE(?, retry_count),
                    retry_delay = COALESCE(?, retry_delay),
                    expected_status_code = COALESCE(?, expected_status_code),
                    ssl_enabled = COALESCE(?, ssl_enabled),
                    date_modified = ?
                WHERE monitored_service_id = ?
            """);

            monitoredServiceDto(name, url, region, checkInterval, retryCount, retryDelay, expectedStatus, psUpdate);
            psUpdate.setObject(8, sslEnabled);
            psUpdate.setTimestamp(9, now);
            psUpdate.setLong(10, serviceId);

            psUpdate.executeUpdate();

            AuditLogger.log(exchange, "monitored_services", serviceId,
                    "UPDATE", beforeData, HttpRequestUtil.toJsonString(body));

            ResponseUtil.sendSuccess(exchange, "Monitored service updated",
                    Map.of("monitored_service_id", serviceId));

        } catch (Exception e) {
            logger.error("Upsert failed: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    public static void monitoredServiceDto(String name, String url, String region, Integer checkInterval, Integer retryCount, Integer retryDelay, Integer expectedStatus, PreparedStatement psUpdate) throws SQLException {
        psUpdate.setString(1, name);
        psUpdate.setString(2, url);
        psUpdate.setString(3, region);
        psUpdate.setObject(4, checkInterval);
        psUpdate.setObject(5, retryCount);
        psUpdate.setObject(6, retryDelay);
        psUpdate.setObject(7, expectedStatus);
    }
}
