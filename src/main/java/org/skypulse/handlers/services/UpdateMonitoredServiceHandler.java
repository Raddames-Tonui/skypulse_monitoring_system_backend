package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;

import static org.skypulse.handlers.services.CreateMonitoredServiceHandler.setServiceParams;

/**
 * UPDATE Monitored Services (Requires UUID)
 */
@RequireRoles({"ADMIN", "OPERATOR"})
public class UpdateMonitoredServiceHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(UpdateMonitoredServiceHandler.class);

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

        String uuid = HttpRequestUtil.getString(body, "uuid");

        if (uuid == null) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing required field: uuid for update operation");
            return;
        }

        String name = HttpRequestUtil.getString(body, "monitored_service_name");
        String url = HttpRequestUtil.getString(body, "monitored_service_url");
        String region = HttpRequestUtil.getString(body, "monitored_service_region");
        Integer checkInterval = HttpRequestUtil.getInteger(body, "check_interval");
        Integer retryCount = HttpRequestUtil.getInteger(body, "retry_count");
        Integer retryDelay = HttpRequestUtil.getInteger(body, "retry_delay");
        Integer expectedStatus = HttpRequestUtil.getInteger(body, "expected_status_code");
        Object sslEnabled = body.get("ssl_enabled");

        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            if (url != null) {
                PreparedStatement checkUrl = conn.prepareStatement(
                        "SELECT uuid FROM monitored_services WHERE monitored_service_url = ? AND uuid != ?"
                );
                checkUrl.setString(1, url);
                checkUrl.setString(2, uuid);
                ResultSet rsCheck = checkUrl.executeQuery();
                if (rsCheck.next()) {
                    ResponseUtil.sendError(exchange, StatusCodes.CONFLICT,
                            "Monitored service URL already exists for another service");
                    return;
                }
                rsCheck.close();
                checkUrl.close();
            }

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
            rsOld.close();
            getOld.close();

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
            psUpdate.close();

            AuditLogger.log(exchange, "monitored_services", serviceId,
                    "UPDATE", beforeData, HttpRequestUtil.toJsonString(body));

            ResponseUtil.sendSuccess(exchange, "Monitored service updated",
                    Map.of("uuid", uuid));

        } catch (Exception e) {
            logger.error("Update failed: {}", e.getMessage(), e);
            ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}