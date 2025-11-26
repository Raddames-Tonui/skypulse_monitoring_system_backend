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

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, 401, "Unauthorized");
            return;
        }

        long userId = ctx.userId();

        Map<String, Object> data = new HashMap<>();
        data.put("uuid", ctx.uuid().toString());
        data.put("full_name", ctx.firstName() + " " + ctx.lastName());
        data.put("email", ctx.email());
        data.put("role_name", ctx.roleName());
        data.put("company_name", ctx.companyName());

        Map<String, Object> pref = new HashMap<>();
        pref.put("alert_channel", "email");
        pref.put("receive_weekly_reports", true);
        pref.put("language", "en");
        pref.put("timezone", "UTC");
        pref.put("dashboard_layout", new HashMap<>());

        List<Map<String, Object>> contacts = new ArrayList<>();


        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()){
            String prefSql = """
                SELECT alert_channel, receive_weekly_reports, language, timezone, dashboard_layout
                FROM user_preferences
                WHERE user_id = ?
            """;
            try (PreparedStatement ps = conn.prepareStatement(prefSql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        pref.put("alert_channel", rs.getString("alert_channel"));
                        pref.put("receive_weekly_reports", rs.getBoolean("receive_weekly_reports"));
                        pref.put("language", rs.getString("language"));
                        pref.put("timezone", rs.getString("timezone"));
                        Object layout = rs.getObject("dashboard_layout");
                        if (layout != null) {
                            pref.put("dashboard_layout", layout);
                        }
                    }
                }
            }

            String contactsSql = """
                SELECT type, value, verified, is_primary
                FROM user_contacts
                WHERE user_id = ?
            """;
            try (PreparedStatement ps = conn.prepareStatement(contactsSql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> cont = new HashMap<>();
                        cont.put("contact_type", rs.getString("type"));
                        cont.put("value", rs.getString("value"));
                        cont.put("verified", rs.getBoolean("verified"));
                        cont.put("is_primary", rs.getBoolean("is_primary"));
                        contacts.add(cont);
                    }
                }
            }
        }

        data.put("user_preferences", pref);
        data.put("user_contacts", contacts);

        ResponseUtil.sendSuccess(exchange, "Profile fetched successfully", data);
    }
}
