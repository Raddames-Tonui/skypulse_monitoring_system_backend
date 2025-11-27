package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.rest.auth.RequireRoles;
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
 * CREATE Monitored Services
 */
@RequireRoles({"ADMIN", "OPERATOR"})
public class CreateMonitoredServiceHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreateMonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, StatusCodes.UNAUTHORIZED, "User context missing");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid or empty JSON body");
            return;
        }

        String name = HttpRequestUtil.getString(body, "monitored_service_name");
        String url = HttpRequestUtil.getString(body, "monitored_service_url");

        if (name == null || url == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required fields: name or url");
            return;
        }

        if (body.containsKey("uuid")) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "UUID should not be provided for service creation");
            return;
        }

        String region = HttpRequestUtil.getString(body, "monitored_service_region");
        Integer checkInterval = HttpRequestUtil.getInteger(body, "check_interval");
        Integer retryCount = HttpRequestUtil.getInteger(body, "retry_count");
        Integer retryDelay = HttpRequestUtil.getInteger(body, "retry_delay");
        Integer expectedStatus = HttpRequestUtil.getInteger(body, "expected_status_code");
        Boolean sslEnabled = (Boolean) body.getOrDefault("ssl_enabled", true);

        long userId = JwtUtil.getUserIdFromUuid(ctx.uuid().toString());
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            PreparedStatement checkUrl = conn.prepareStatement(
                    "SELECT uuid FROM monitored_services WHERE monitored_service_url = ?"
            );
            checkUrl.setString(1, url);
            ResultSet rsCheck = checkUrl.executeQuery();
            if (rsCheck.next()) {
                ResponseUtil.sendError(exchange, StatusCodes.CONFLICT,
                        "Monitored service URL already exists");
                return;
            }
            rsCheck.close();
            checkUrl.close();

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
            rs.close();
            ps.close();

            AuditLogger.log(exchange, "monitored_services", newId, "CREATE", null,
                    HttpRequestUtil.toJsonString(body));

            ResponseUtil.sendSuccess(exchange, "Monitored service created",
                    Map.of("uuid", newUuid));

        } catch (Exception e) {
            logger.error("Create failed: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Helper method to set common PreparedStatement parameters for monitored services.
     * This is made public static so other handlers can reference it directly.
     */
    public static void setServiceParams(PreparedStatement ps, String name, String url, String region,
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
