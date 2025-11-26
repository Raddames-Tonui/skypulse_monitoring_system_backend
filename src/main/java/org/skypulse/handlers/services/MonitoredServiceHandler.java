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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;


/**
 * CREATE OR UPDATE Monitored Services
 * - Use uuid for update
 * */
public class MonitoredServiceHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(MonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        if (!"ADMIN".equalsIgnoreCase(ctx.roleName()) && !"OPERATOR".equalsIgnoreCase(ctx.roleName())) {
            ResponseUtil.sendError(exchange, StatusCodes.FORBIDDEN, "User not authorized");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid or empty JSON body");
            return;
        }

        String uuid = HttpRequestUtil.getString(body, "uuid");
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

        long userId = JwtUtil.getUserIdFromUuid(ctx.uuid().toString());
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            if (uuid == null) {
                // CREATE
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
                    RETURNING monitored_service_id, uuid;
                """);

                setServiceParams(ps, name, url, region, checkInterval, retryCount, retryDelay, expectedStatus);
                ps.setBoolean(8, sslEnabled);
                ps.setLong(9, userId);
                ps.setTimestamp(10, now);
                ps.setTimestamp(11, now);

                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, "Failed to create monitored service");
                    return;
                }

                long newId = rs.getLong("monitored_service_id");
                String newUuid = rs.getString("uuid");

                AuditLogger.log(exchange, "monitored_services", newId, "CREATE", null,
                        HttpRequestUtil.toJsonString(body));

                ResponseUtil.sendSuccess(exchange, "Monitored service created",
                        Map.of("uuid", newUuid));
                return;
            }

            // UPDATE using UUID
            PreparedStatement getOld = conn.prepareStatement(
                    "SELECT monitored_service_id, row_to_json(t) AS data FROM (SELECT * FROM monitored_services WHERE uuid = ?) t"
            );
            getOld.setString(1, uuid);
            ResultSet rsOld = getOld.executeQuery();
            if (!rsOld.next()) {
                ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Service not found");
                return;
            }

            long serviceId = rsOld.getLong("monitored_service_id");
            String beforeData = rsOld.getString("data");

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
                WHERE uuid = ?
            """);

            setServiceParams(psUpdate, name, url, region, checkInterval, retryCount, retryDelay, expectedStatus);
            psUpdate.setObject(8, sslEnabled);
            psUpdate.setTimestamp(9, now);
            psUpdate.setString(10, uuid);

            psUpdate.executeUpdate();

            AuditLogger.log(exchange, "monitored_services", serviceId,
                    "UPDATE", beforeData, HttpRequestUtil.toJsonString(body));

            ResponseUtil.sendSuccess(exchange, "Monitored service updated",
                    Map.of("uuid", uuid));

        } catch (Exception e) {
            logger.error("Upsert failed: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static void setServiceParams(PreparedStatement ps, String name, String url, String region,
                                         Integer checkInterval, Integer retryCount, Integer retryDelay,
                                         Integer expectedStatus) throws SQLException {
        ps.setString(1, name);
        ps.setString(2, url);
        ps.setString(3, region);
        ps.setObject(4, checkInterval);
        ps.setObject(5, retryCount);
        ps.setObject(6, retryDelay);
        ps.setObject(7, expectedStatus);
    }
}
