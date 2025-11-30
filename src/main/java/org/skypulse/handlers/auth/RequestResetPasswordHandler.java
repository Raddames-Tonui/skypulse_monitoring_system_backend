package org.skypulse.handlers.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.KeyProvider;
import org.skypulse.utils.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class RequestResetPasswordHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(RequestResetPasswordHandler.class);
    private final ObjectMapper mapper = JsonUtil.mapper();

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        try {
            Map<String, Object> input = mapper.readValue(exchange.getInputStream(), Map.class);
            if (input == null || input.get("email") == null || ((String) input.get("email")).isBlank()) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Email is required");
                return;
            }

            String email = ((String) input.get("email")).trim().toLowerCase();

            try (Connection conn = JdbcUtils.getConnection()) {
                conn.setAutoCommit(false);

                Long userId = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT user_id FROM users WHERE LOWER(user_email) = ? AND is_deleted = FALSE"
                )) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getLong("user_id");
                        } else {
                            // Do not reveal if email exists
                            conn.commit();
                            ResponseUtil.sendSuccess(exchange,
                                    "If the email exists in our system, a password reset link will be sent.",
                                    Map.of());
                            return;
                        }
                    }
                }

                String token = PasswordUtil.generateToken();
                Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO user_password_tokens (user_id, token, expires_at) VALUES (?, ?, ?)"
                )) {
                    ps.setLong(1, userId);
                    ps.setString(2, token);
                    ps.setTimestamp(3, Timestamp.from(expiresAt));
                    ps.executeUpdate();
                }

                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("userId", userId);
                eventPayload.put("email", email);

                String frontendBaseUrl = KeyProvider.getFrontendBaseUrl();
                eventPayload.put("resetLink", frontendBaseUrl + "/auth/reset-password?token=" + token);

                eventPayload.put("message", "Password reset requested");


                String payloadJson = JsonUtil.mapper().writeValueAsString(eventPayload);

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO event_outbox (event_type, payload, status) VALUES ('RESET_PASSWORD', ?::jsonb, 'PENDING')"
                )) {
                    ps.setString(1, payloadJson);
                    ps.executeUpdate();
                }

                conn.commit();

                ResponseUtil.sendSuccess(exchange,
                        "If the email exists in our system, a password reset link will be sent.",
                        Map.of());

            } catch (Exception e) {
                logger.error("Failed to request password reset for {}", email, e);
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
                        "Unexpected server error. Please try again later.");
            }

        } catch (Exception ex) {
            logger.error("Invalid request body for password reset", ex);
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid input: " + ex.getMessage());
        }
    }
}
