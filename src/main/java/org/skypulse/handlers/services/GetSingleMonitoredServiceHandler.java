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
        logger.debug("GetSingleMonitoredServiceDetailHandler uuid={}", uuid);

        UUID uuidValue;
        try {
            uuidValue = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String sql = """
            SELECT
                ms.monitored_service_id, ms.uuid, ms.monitored_service_name, ms.monitored_service_url,
                ms.monitored_service_region, ms.check_interval, ms.retry_count, ms.retry_delay,
                ms.expected_status_code, ms.ssl_enabled, ms.last_uptime_status, ms.consecutive_failures,
                ms.last_checked, ms.created_by, ms.date_created, ms.date_modified, ms.is_active,

                u.user_id AS creator_id, u.first_name AS creator_first_name, u.last_name AS creator_last_name,
                u.user_email AS creator_email,

                cgm.contact_group_id, cg.uuid AS group_uuid, cg.contact_group_name, cg.contact_group_description,

                ul.uptime_log_id, ul.status AS uptime_status, ul.response_time_ms, ul.http_status, ul.error_message,
                ul.checked_at AS uptime_checked_at,

                ssl.ssl_log_id, ssl.domain AS ssl_domain, ssl.issuer AS ssl_issuer, ssl.serial_number AS ssl_serial_number,
                ssl.signature_algorithm AS ssl_signature_algo, ssl.public_key_algo AS ssl_pubkey_algo,
                ssl.public_key_length AS ssl_pubkey_length, ssl.san_list AS ssl_san_list, ssl.chain_valid AS ssl_chain_valid,
                ssl.subject AS ssl_subject, ssl.fingerprint AS ssl_fingerprint, ssl.issued_date AS ssl_issued_date,
                ssl.expiry_date AS ssl_expiry_date, ssl.days_remaining AS ssl_days_remaining, ssl.last_checked AS ssl_last_checked,

                inc.incident_id, inc.uuid AS incident_uuid, inc.started_at AS incident_started_at, inc.resolved_at AS incident_resolved_at,
                inc.duration_minutes, inc.cause AS incident_cause, inc.status AS incident_status,

                mw.maintenance_window_id, mw.uuid AS maintenance_uuid, mw.start_time AS maintenance_start,
                mw.end_time AS maintenance_end, mw.reason AS maintenance_reason, mw.created_by AS maintenance_created_by
            FROM monitored_services ms
            LEFT JOIN users u ON ms.created_by = u.user_id
            LEFT JOIN monitored_services_contact_groups cgm ON ms.monitored_service_id = cgm.monitored_service_id
            LEFT JOIN contact_groups cg ON cgm.contact_group_id = cg.contact_group_id
            LEFT JOIN LATERAL (
                SELECT *
                FROM uptime_logs ul
                WHERE ul.monitored_service_id = ms.monitored_service_id
                ORDER BY ul.checked_at DESC
                LIMIT 20
            ) ul ON true
            LEFT JOIN ssl_logs ssl ON ms.monitored_service_id = ssl.monitored_service_id
            LEFT JOIN incidents inc ON ms.monitored_service_id = inc.monitored_service_id
            LEFT JOIN maintenance_windows mw ON ms.monitored_service_id = mw.monitored_service_id
            WHERE ms.uuid = ?
        """;

        List<Map<String, Object>> rows = DatabaseUtils.query(sql, List.of(uuidValue));
        if (rows.isEmpty()) {
            ResponseUtil.sendError(exchange, StatusCodes.NOT_FOUND, "Service not found");
            return;
        }

        Map<String, Object> service = new HashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Map<String, Object>> uptimeLogs = new ArrayList<>();
        List<Map<String, Object>> sslLogs = new ArrayList<>();
        List<Map<String, Object>> incidents = new ArrayList<>();
        List<Map<String, Object>> maintenanceWindows = new ArrayList<>();

        Set<Long> seenGroups = new HashSet<>();
        Set<Long> seenUptime = new HashSet<>();
        Set<Long> seenSsl = new HashSet<>();
        Set<Long> seenIncidents = new HashSet<>();
        Set<Long> seenMaintenance = new HashSet<>();

        for (Map<String, Object> row : rows) {
            if (service.isEmpty()) {
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
            }

            // Contact groups
            Object groupIdObj = row.get("contact_group_id");
            if (groupIdObj != null) {
                long groupId = ((Number) groupIdObj).longValue();
                if (!seenGroups.contains(groupId)) {
                    Map<String, Object> group = new HashMap<>();
                    group.put("id", groupId);
                    group.put("uuid", row.get("group_uuid"));
                    group.put("name", row.get("contact_group_name"));
                    group.put("description", row.get("contact_group_description"));
                    groups.add(group);
                    seenGroups.add(groupId);
                }
            }

            // Uptime logs
            Object uptimeIdObj = row.get("uptime_log_id");
            if (uptimeIdObj != null) {
                long uptimeId = ((Number) uptimeIdObj).longValue();
                if (!seenUptime.contains(uptimeId)) {
                    Map<String, Object> log = new HashMap<>();
                    log.put("id", uptimeId);
                    log.put("status", row.get("uptime_status"));
                    log.put("response_time_ms", row.get("response_time_ms"));
                    log.put("http_status", row.get("http_status"));
                    log.put("error_message", row.get("error_message"));
                    log.put("checked_at", row.get("uptime_checked_at"));
                    uptimeLogs.add(log);
                    seenUptime.add(uptimeId);
                }
            }

            // SSL logs
            Object sslIdObj = row.get("ssl_log_id");
            if (sslIdObj != null) {
                long sslId = ((Number) sslIdObj).longValue();
                if (!seenSsl.contains(sslId)) {
                    Map<String, Object> sslLog = new HashMap<>();
                    sslLog.put("id", sslId);
                    sslLog.put("domain", row.get("ssl_domain"));
                    sslLog.put("issuer", row.get("ssl_issuer"));
                    sslLog.put("serial_number", row.get("ssl_serial_number"));
                    sslLog.put("signature_algorithm", row.get("ssl_signature_algo"));
                    sslLog.put("public_key_algo", row.get("ssl_pubkey_algo"));
                    sslLog.put("public_key_length", row.get("ssl_pubkey_length"));
                    sslLog.put("san_list", row.get("ssl_san_list"));
                    sslLog.put("chain_valid", row.get("ssl_chain_valid"));
                    sslLog.put("subject", row.get("ssl_subject"));
                    sslLog.put("fingerprint", row.get("ssl_fingerprint"));
                    sslLog.put("issued_date", row.get("ssl_issued_date"));
                    sslLog.put("expiry_date", row.get("ssl_expiry_date"));
                    sslLog.put("days_remaining", row.get("ssl_days_remaining"));
                    sslLog.put("last_checked", row.get("ssl_last_checked"));
                    sslLogs.add(sslLog);
                    seenSsl.add(sslId);
                }
            }

            // Incidents
            Object incidentIdObj = row.get("incident_id");
            if (incidentIdObj != null) {
                long incidentId = ((Number) incidentIdObj).longValue();
                if (!seenIncidents.contains(incidentId)) {
                    Map<String, Object> incident = new HashMap<>();
                    incident.put("id", incidentId);
                    incident.put("uuid", row.get("incident_uuid"));
                    incident.put("started_at", row.get("incident_started_at"));
                    incident.put("resolved_at", row.get("incident_resolved_at"));
                    incident.put("duration_minutes", row.get("duration_minutes"));
                    incident.put("cause", row.get("incident_cause"));
                    incident.put("status", row.get("incident_status"));
                    incidents.add(incident);
                    seenIncidents.add(incidentId);
                }
            }

            // Maintenance windows
            Object maintenanceIdObj = row.get("maintenance_window_id");
            if (maintenanceIdObj != null) {
                long maintenanceId = ((Number) maintenanceIdObj).longValue();
                if (!seenMaintenance.contains(maintenanceId)) {
                    Map<String, Object> mw = new HashMap<>();
                    mw.put("id", maintenanceId);
                    mw.put("uuid", row.get("maintenance_uuid"));
                    mw.put("start_time", row.get("maintenance_start"));
                    mw.put("end_time", row.get("maintenance_end"));
                    mw.put("reason", row.get("maintenance_reason"));
                    mw.put("created_by", row.get("maintenance_created_by"));
                    maintenanceWindows.add(mw);
                    seenMaintenance.add(maintenanceId);
                }
            }
        }

        service.put("contact_groups", groups);
        service.put("uptime_logs", uptimeLogs);
        service.put("ssl_logs", sslLogs);
        service.put("incidents", incidents);
        service.put("maintenance_windows", maintenanceWindows);

        ResponseUtil.sendSuccess(exchange, "Monitored Service fetched successfully.", service);
    }
}
