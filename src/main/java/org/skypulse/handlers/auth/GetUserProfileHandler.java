package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.config.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class GetUserProfileHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(GetUserProfileHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ResponseUtil.sendError(exchange, 400, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring("Bearer ".length());
        String userUuid = JwtUtil.getUserUUId(token);
        if (userUuid == null) {
            ResponseUtil.sendError(exchange, 400, "Missing or invalid token");
            return;
        }

        try (Connection conn = Objects.requireNonNull(DatabaseManager.getDataSource()).getConnection()) {

            // Fetch user + role + preferences
            String userSql = """
                SELECT u.user_id, u.uuid, u.first_name, u.last_name, u.user_email, u.is_active, 
                       u.role_id, r.role_name,
                        up.alert_channel, up.receive_weekly_reports, 
                       up.language, up.timezone, up.dashboard_layout
                FROM users u
                LEFT JOIN roles r ON r.role_id = u.role_id
                LEFT JOIN user_preferences up ON up.user_id = u.user_id
                WHERE u.uuid = ?::uuid
            """;

            long userId;
            long roleId;
            String firstName;
            String lastName;
            String email;
            String roleName;
            boolean isActive;

            Map<String, Object> preferences = new HashMap<>();

            try (PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setString(1, userUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ResponseUtil.sendError(exchange, 404, "User not found");
                        return;
                    }

                    userId = rs.getLong("user_id");
                    roleId = rs.getLong("role_id");
                    firstName = rs.getString("first_name");
                    lastName = rs.getString("last_name");
                    email = rs.getString("user_email");
                    roleName = rs.getString("role_name");
                    isActive = rs.getBoolean("is_active");

                    preferences.put("alert_channel", rs.getString("alert_channel"));
                    preferences.put("receive_weekly_reports", rs.getBoolean("receive_weekly_reports"));
                    preferences.put("language", rs.getString("language"));
                    preferences.put("timezone", rs.getString("timezone"));
                    preferences.put("dashboard_layout", rs.getString("dashboard_layout"));
                }
            }



            Map<String, Object> data = new HashMap<>();
            data.put("uuid", userUuid);
            data.put("full_name", firstName + lastName);
            data.put("email", email);
            data.put("role", roleName);
            data.put("is_active", isActive);
            data.put("preferences", preferences);

            ResponseUtil.sendSuccess(exchange, "Profile fetched successfully", data);

        } catch (Exception e) {
            logger.error("Error fetching user profile", e);
            ResponseUtil.sendError(exchange, 500, "Internal Server Error");
        }
    }
}
