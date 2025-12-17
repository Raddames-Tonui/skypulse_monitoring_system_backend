package org.skypulse.handlers.services.service;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;

import org.skypulse.config.database.DatabaseUtils;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class GetSingleServiceHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(GetSingleServiceHandler.class);

    private static final int LOG_LIMIT = 300;

    @Override
    public void handleRequest(HttpServerExchange ex) throws Exception {

        String uuid = Optional.ofNullable(ex.getQueryParameters().get("uuid"))
                .map(Deque::peekFirst)
                .orElse(null);

        if (uuid == null) {
            ResponseUtil.sendError(ex, StatusCodes.BAD_REQUEST, "Missing uuid parameter");
            return;
        }

        UUID uuidObj;
        try {
            uuidObj = UUID.fromString(uuid);
        } catch (Exception e) {
            ResponseUtil.sendError(ex, StatusCodes.BAD_REQUEST, "Invalid UUID");
            return;
        }

        log.debug("Fetching service dashboard for uuid={}", uuid);


        String serviceSql = """
            SELECT
                ms.monitored_service_id,
                ms.uuid,
                ms.monitored_service_name AS service_name,
                ms.monitored_service_url AS url,
                ms.monitored_service_region AS region,
                ms.check_interval,
                ms.retry_count,
                ms.retry_delay,
                ms.expected_status_code,
                ms.ssl_enabled,
                ms.last_uptime_status,
                ms.consecutive_failures,
                ms.date_created,
                ms.date_modified,

                u.uuid AS creator_id,
                u.first_name AS creator_first_name,
                u.last_name AS creator_last_name,
                u.user_email AS creator_email,

                cg.contact_group_id,
                cg.uuid AS contact_group_uuid,
                cg.contact_group_name,
                cg.contact_group_description,

                sl.days_remaining
             FROM monitored_services ms
             LEFT JOIN users u ON ms.created_by = u.user_id
             LEFT JOIN monitored_services_contact_groups mcg
                    ON ms.monitored_service_id = mcg.monitored_service_id
             LEFT JOIN contact_groups cg
                    ON mcg.contact_group_id = cg.contact_group_id
             LEFT JOIN ssl_logs sl
                    ON sl.monitored_service_id = ms.monitored_service_id
             WHERE ms.uuid = ?
        """;

        List<Map<String, Object>> rows = DatabaseUtils.query(serviceSql, List.of(uuidObj));
        if (rows.isEmpty()) {
            ResponseUtil.sendError(ex, StatusCodes.NOT_FOUND, "Service not found");
            return;
        }

        Map<String, Object> base = rows.getFirst();
        long svcId = ((Number) base.get("monitored_service_id")).longValue();


        Map<String, Object> service = new HashMap<>();

        service.put("uuid", base.get("uuid"));
        service.put("service_name", base.get("service_name"));
        service.put("url", base.get("url"));
        service.put("region", base.get("region"));
        service.put("check_interval", base.get("check_interval"));
        service.put("retry_count", base.get("retry_count"));
        service.put("retry_delay", base.get("retry_delay"));
        service.put("expected_status_code", base.get("expected_status_code"));
        service.put("ssl_enabled", base.get("ssl_enabled"));
        service.put("last_uptime_status", base.get("last_uptime_status"));
        service.put("consecutive_failures", base.get("consecutive_failures"));
        service.put("date_created", base.get("date_created"));
        service.put("date_modified", base.get("date_modified"));
        service.put("ssl_days_remaining", base.get("days_remaining"));

        Map<String, Object> creator = new HashMap<>();
        creator.put("creator_uuid", base.get("creator_id"));
        creator.put("first_name", base.get("creator_first_name"));
        creator.put("last_name", base.get("creator_last_name"));
        creator.put("email", base.get("creator_email"));
        service.put("created_by", creator);

        List<Map<String, Object>> groups = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (Map<String, Object> r : rows) {
            if (r.get("contact_group_id") == null) continue;

            long id = ((Number) r.get("contact_group_id")).longValue();
            if (seen.add(id)) {
                Map<String, Object> cg = new HashMap<>();
                cg.put("id", id);
                cg.put("uuid", r.get("contact_group_uuid"));
                cg.put("name", r.get("contact_group_name"));
                cg.put("description", r.get("contact_group_description"));
                groups.add(cg);
            }
        }

        service.put("contact_groups", groups);

                // Summary of uptime and response time averages
        String summarySql = """
                                SELECT
                                    -- 1 day averages
                                    AVG(response_time_ms) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 day') AS avg_resp_1d,
                                    100.0 * SUM(CASE WHEN status='UP' THEN 1 ELSE 0 END) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 day') /
                                           COUNT(*) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 day') AS uptime_1d,
                            
                                    -- 7 days averages
                                    AVG(response_time_ms) FILTER (WHERE checked_at >= NOW() - INTERVAL '7 day') AS avg_resp_7d,
                                    100.0 * SUM(CASE WHEN status='UP' THEN 1 ELSE 0 END) FILTER (WHERE checked_at >= NOW() - INTERVAL '7 day') /
                                           COUNT(*) FILTER (WHERE checked_at >= NOW() - INTERVAL '7 day') AS uptime_7d,
                            
                                    -- 1 month averages
                                    AVG(response_time_ms) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 month') AS avg_resp_30d,
                                    100.0 * SUM(CASE WHEN status='UP' THEN 1 ELSE 0 END) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 month') /
                                           COUNT(*) FILTER (WHERE checked_at >= NOW() - INTERVAL '1 month') AS uptime_30d
                                FROM uptime_logs
                                WHERE monitored_service_id = ?
                            """;

        Map<String, Object> summary = DatabaseUtils.query(summarySql, List.of(svcId))
                .stream()
                .findFirst()
                .orElse(Map.of());

        service.put("uptime_summary", Map.of(
                "avg_response_time_1d", summary.get("avg_resp_1d"),
                "uptime_percentage_1d", summary.get("uptime_1d"),
                "avg_response_time_7d", summary.get("avg_resp_7d"),
                "uptime_percentage_7d", summary.get("uptime_7d"),
                "avg_response_time_30d", summary.get("avg_resp_30d"),
                "uptime_percentage_30d", summary.get("uptime_30d")
        ));


        // Maintenance windows
        String mwSql = """
                SELECT
                    uuid,
                    window_name,
                    start_time,
                    end_time,
                    reason,
                    created_by
                FROM maintenance_windows
                WHERE monitored_service_id = ?
                ORDER BY start_time DESC
            """;


        service.put(
                "maintenance_windows",
                DatabaseUtils.query(mwSql, List.of(svcId))
        );


        ResponseUtil.sendSuccess(ex, "Full dashboard data fetched", service);
    }

    private Double calcAvg(List<Map<String, Object>> rows) {
        double sum = 0;
        int count = 0;

        for (Map<String, Object> r : rows) {
            Object v = r.get("response_time_ms");
            if (v instanceof Number n) {
                sum += n.doubleValue();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private Double calcUptimePercent(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return null;
        long up = rows.stream()
                .filter(r -> "UP".equalsIgnoreCase(String.valueOf(r.get("status"))))
                .count();
        return up * 100.0 / rows.size();
    }
}
