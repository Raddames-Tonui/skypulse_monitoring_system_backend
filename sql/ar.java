package org.skypulse.handlers.services;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GetSingleMonitoredServiceHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(GetSingleMonitoredServiceHandler.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (HttpRequestUtil.dispatchIfIoThread(exchange, this)) return;

        Deque<String> uuidParam = exchange.getQueryParameters().get("uuid");
        if (uuidParam == null || uuidParam.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Missing uuid parameter");
            return;
        }

        String uuid = uuidParam.getFirst();
        logger.debug("GetSingleMonitoredServiceHandler uuid={}", uuid);

        UUID uuidValue;
        try {
            uuidValue = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        //  aggregate last 30 uptime logs + all related arrays
        String sql = """
            WITH recent_uptime_logs AS (
                SELECT *
                FROM (
                    SELECT ul.*,
                           ROW_NUMBER() OVER (PARTITION BY ul.monitored_service_id ORDER BY ul.checked_at DESC) AS rn
                    FROM uptime_logs ul
                ) ranked
                WHERE rn <= 30
            )
            SELECT
                ms.monitored_service_id,
                ms.uuid,
                ms.monitored_service_name,
                ms.monitored_service_url,
                ms.monitored_service_region,
                ms.check_interval,
                ms.retry_count,
                ms.retry_delay,
                ms.expected_status_code,
                ms.ssl_enabled,
                ms.last_uptime_status,
                ms.consecutive_failures,
                ms.last_checked,
                ms.created_by,
                ms.date_created,
                ms.date_modified,
                ms.is_active,

                u.user_id AS creator_id,
                u.first_name AS creator_first_name,
                u.last_name AS creator_last_name,
                u.user_email AS creator_email,

                cgm.contact_group_id,
                cg.uuid AS group_uuid,
                cg.contact_group_name,
                cg.contact_group_description,

                COLLECT_LIST(STRUCT(
                    ul.uptime_log_id AS id,
                    ul.status AS status,
                    ul.response_time_ms,
                    ul.http_status,
                    ul.error_message,
                    ul.region AS region,
                    ul.checked_at AS checked_at
                )) AS uptime_logs,

                COLLECT_LIST(STRUCT(
                    ssl.ssl_log_id AS id,
                    ssl.domain,
                    ssl.issuer,
                    ssl.serial_number,
                    ssl.signature_algorithm,
                    ssl.public_key_algo,
                    ssl.public_key_length,
                    ssl.san_list,
                    ssl.chain_valid,
                    ssl.subject,
                    ssl.fingerprint,
                    ssl.issued_date,
                    ssl.expiry_date,
                    ssl.days_remaining,
                    ssl.last_checked
                )) AS ssl_logs,
     
                COLLECT_LIST(STRUCT(
                    inc.incident_id AS id,
                    inc.uuid AS uuid,
                    inc.started_at,
                    inc.resolved_at,
                    inc.duration_minutes,
                    inc.cause,
                    inc.status
                )) AS incidents,
        
                COLLECT_LIST(STRUCT(
                    mw.maintenance_window_id AS id,
                    mw.uuid AS uuid,
                    mw.start_time,
                    mw.end_time,
                    mw.reason,
                    mw.created_by
                )) AS maintenance_windows

            FROM monitored_services ms
            LEFT JOIN users u ON ms.created_by = u.user_id
            LEFT JOIN monitored_services_contact_groups cgm ON ms.monitored_service_id = cgm.monitored_service_id
            LEFT JOIN contact_groups cg ON cgm.contact_group_id = cg.contact_group_id
            LEFT JOIN recent_uptime_logs ul ON ms.monitored_service_id = ul.monitored_service_id
            LEFT JOIN ssl_logs ssl ON ms.monitored_service_id = ssl.monitored_service_id
            LEFT JOIN incidents inc ON ms.monitored_service_id = inc.monitored_service_id
            LEFT JOIN maintenance_windows mw ON ms.monitored_service_id = mw.monitored_service_id
            WHERE ms.uuid = ?
            GROUP BY
                ms.monitored_service_id,
                ms.uuid,
                ms.monitored_service_name,
                ms.monitored_service_url,
                ms.monitored_service_region,
                ms.check_interval,
                ms.retry_count,
                ms.retry_delay,
                ms.expected_status_code,
                ms.ssl_enabled,
                ms.last_uptime_status,
                ms.consecutive_failures,
                ms.last_checked,
                ms.created_by,
                ms.date_created,
                ms.date_modified,
                ms.is_active,
                u.user_id,
                u.first_name,
                u.last_name,
                u.user_email,
                cgm.contact_group_id,
                cg.uuid,
                cg.contact_group_name,
                cg.contact_group_description
        """;

        List<Map<String, Object>> rows = DatabaseUtils.query(sql, List.of(uuidValue));
        if (rows.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Service not found");
            return;
        }

        Map<String, Object> row = rows.getFirst();

        Map<String, Object> service = new HashMap<>();
        service.put("uuid", row.get("uuid"));
        service.put("name", row.get("monitored_service_name"));
        service.put("url", row.get("monitored_service_url"));
        service.put("region", row.get("monitored_service_region"));
        service.put("check_interval", row.get("check_interval"));
        service.put("retry_count", row.get("retry_count"));
        service.put("retry_delay", row.get("retry_delay"));
        service.put("expected_status_code", row.get("expected_status_code"));
        service.put("ssl_enabled", row.get("ssl_enabled"));
        service.put("last_uptime_status", row.get("last_uptime_status"));
        service.put("consecutive_failures", row.get("consecutive_failures"));
        service.put("last_checked", row.get("last_checked"));
        service.put("date_created", row.get("date_created"));
        service.put("date_modified", row.get("date_modified"));
        service.put("is_active", row.get("is_active"));

        Map<String, Object> creator = new HashMap<>();
        creator.put("id", row.get("creator_id"));
        creator.put("first_name", row.get("creator_first_name"));
        creator.put("last_name", row.get("creator_last_name"));
        creator.put("email", row.get("creator_email"));
        service.put("created_by", creator);

        service.put("contact_groups", row.get("contact_group_id") != null ? List.of(row) : Collections.emptyList());
        service.put("uptime_logs", row.get("uptime_logs"));
        service.put("ssl_logs", row.get("ssl_logs"));
        service.put("incidents", row.get("incidents"));
        service.put("maintenance_windows", row.get("maintenance_windows"));

        ResponseUtil.sendSuccess(exchange, "Monitored Service fetched successfully.", service);
    }
}
