package org.skypulse.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.DatabaseManager;
import org.skypulse.utils.security.PasswordUtil;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

public class AuthRegisterHandler implements HttpHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (!exchange.getRequestMethod().equalToString("POST")) {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            exchange.getResponseSender().send("Method Not Allowed");
            return;
        }

        try (InputStream is = exchange.getInputStream()) {
            Map<String, String> body = objectMapper.readValue(is, Map.class);

            // Required fields
            String firstName = body.get("first_name");
            String lastName  = body.get("last_name");
            String email     = body.get("user_email");
            String password  = body.get("password");
            String contactType  = body.get("contact_type");
            String contactValue = body.get("contact_value");

            if (firstName == null || lastName == null || email == null || password == null
                    || contactType == null || contactValue == null) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.getResponseSender().send("Missing required fields");
                return;
            }

            DataSource ds = DatabaseManager.getDataSource();
            if (ds == null) {
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.getResponseSender().send("Database not initialized");
                return;
            }

            try (Connection conn = ds.getConnection()) {
                conn.setAutoCommit(false);

                // Insert into users
                String insertUserSql = """
                    INSERT INTO users (first_name, last_name, user_email, password_hash)
                    VALUES (?, ?, ?, ?)
                    RETURNING user_id;
                    """;

                long userId;
                try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                    ps.setString(1, firstName);                 // no encryption
                    ps.setString(2, lastName);                  // no encryption
                    ps.setString(3, email);
                    ps.setString(4, PasswordUtil.hashPassword(password)); // password hashed

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getLong(1);
                        } else {
                            conn.rollback();
                            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                            exchange.getResponseSender().send("Failed to create user");
                            return;
                        }
                    }
                }

                // Insert primary contact
                String insertContactSql = """
                    INSERT INTO user_contacts (user_id, type, value, is_primary, verified)
                    VALUES (?, ?, ?, TRUE, FALSE);
                    """;
                try (PreparedStatement ps = conn.prepareStatement(insertContactSql)) {
                    ps.setLong(1, userId);
                    ps.setString(2, contactType);
                    ps.setString(3, contactValue);
                    ps.executeUpdate();
                }

                // Insert default preferences
                String insertPrefsSql = "INSERT INTO user_preferences (user_id) VALUES (?);";
                try (PreparedStatement ps = conn.prepareStatement(insertPrefsSql)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }

                conn.commit();

                exchange.setStatusCode(StatusCodes.CREATED);
                exchange.getResponseSender().send("{\"user_id\": " + userId + "}");

            } catch (Exception e) {
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.getResponseSender().send("Signup failed: " + e.getMessage());
            }
        }
    }
}
