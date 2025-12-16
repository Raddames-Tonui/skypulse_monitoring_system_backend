package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.ResponseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class GetUserProfileHandler implements HttpHandler {

    public Map<String, Object> getUserProfile(long userId) throws Exception {

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> preferences = new HashMap<>();
        List<Map<String, Object>> contacts = new ArrayList<>();

        String sql = """
            SELECT
                u.user_id,
                u.uuid,
                u.first_name,
                u.last_name,
                u.user_email,
                r.role_name,
                c.company_name,

                up.alert_channel,
                up.receive_weekly_reports,
                up.language,
                up.timezone,
                up.dashboard_layout,

                uc.type        AS contact_type,
                uc.value       AS contact_value,
                uc.verified    AS contact_verified,
                uc.is_primary  AS contact_is_primary

            FROM users u
            LEFT JOIN roles r ON r.role_id = u.role_id
            LEFT JOIN company c ON c.company_id = u.company_id
            LEFT JOIN user_preferences up ON up.user_id = u.user_id
            LEFT JOIN user_contacts uc ON uc.user_id = u.user_id
            WHERE u.user_id = ?
        """;

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, userId);

            try (ResultSet rs = ps.executeQuery()) {

                boolean preferencesInitialized = false;

                while (rs.next()) {

                    if (data.isEmpty()) {
                        data.put("user_id", rs.getLong("user_id"));
                        data.put("uuid", rs.getObject("uuid"));
                        data.put("first_name", rs.getString("first_name"));
                        data.put("last_name", rs.getString("last_name"));
                        data.put("email", rs.getString("user_email"));
                        data.put("role_name", rs.getString("role_name"));
                        data.put("company_name", rs.getString("company_name"));
                    }

                    if (!preferencesInitialized) {
                        preferences.put("alert_channel",
                                Optional.ofNullable(rs.getString("alert_channel")).orElse("EMAIL"));
                        preferences.put("receive_weekly_reports",
                                rs.getObject("receive_weekly_reports") == null || rs.getBoolean("receive_weekly_reports"));
                        preferences.put("language",
                                Optional.ofNullable(rs.getString("language")).orElse("en"));
                        preferences.put("timezone",
                                Optional.ofNullable(rs.getString("timezone")).orElse("UTC"));
                        preferences.put("dashboard_layout",
                                Optional.ofNullable(rs.getObject("dashboard_layout")).orElse(new HashMap<>()));

                        preferencesInitialized = true;
                    }

                    if (rs.getString("contact_type") != null) {
                        Map<String, Object> contact = new HashMap<>();
                        contact.put("contact_type", rs.getString("contact_type"));
                        contact.put("value", rs.getString("contact_value"));
                        contact.put("verified", rs.getBoolean("contact_verified"));
                        contact.put("is_primary", rs.getBoolean("contact_is_primary"));
                        contacts.add(contact);
                    }
                }
            }
        }

        data.put("user_preferences", preferences);
        data.put("user_contacts", contacts);

        return data;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, 401, "Unauthorized");
            return;
        }

        Map<String, Object> profile = getUserProfile(ctx.userId());
        ResponseUtil.sendSuccess(exchange, "Profile fetched successfully", profile);
    }
}
