package org.skypulse.handlers.users;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.rest.auth.RequireRoles;
import org.skypulse.utils.AuditLogger;
import org.skypulse.utils.JsonUtil;
import org.skypulse.utils.ResponseUtil;
import org.skypulse.utils.security.KeyProvider;
import org.skypulse.utils.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;

@RequireRoles({"ADMIN"})
public class CreateNewUser implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreateNewUser.class);
    private static final Random random = new Random();

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        try {
            Map<String, Object> input = JsonUtil.mapper().readValue(exchange.getInputStream(), Map.class);
            if (input == null) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid JSON");
                return;
            }

            String firstName = (String) input.getOrDefault("first_name", "");
            String lastName = (String) input.getOrDefault("last_name", "");
            String email = (String) input.get("user_email");
            String roleName = (String) input.get("role_name");

            if (!PasswordUtil.isValidEmail(email)) {
                ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid email address");
                return;
            }

            Integer companyId = (Integer) input.get("company_id");

            try (Connection conn = JdbcUtils.getConnection()) {
                conn.setAutoCommit(false);

                if (emailExists(conn, email)) {
                    ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Email already exists");
                    return;
                }

                int roleId = fetchRoleId(conn, roleName);
                if (roleId == -1) {
                    ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Role '" + roleName + "' not found");
                    return;
                }

                UserInfo userInfo = insertUser(conn, firstName, lastName, email, roleId, companyId);

                insertPrimaryContact(conn, userInfo.userId(), email);

                String token = createOneTimeToken(conn, userInfo.userId());

                String frontendBaseUrl = KeyProvider.getFrontendBaseUrl();
                String oneTimeLink = frontendBaseUrl + "/auth/set-password?token=" + token;
                insertEvent(conn, userInfo.userId(), email, oneTimeLink);

                AuditLogger.log(exchange, "users", userInfo.userId(), "CREATE", null, Map.of(
                        "first_name", firstName,
                        "last_name", lastName,
                        "user_email", email,
                        "role_id", roleId,
                        "company_id", companyId
                ));

                conn.commit();

                ResponseUtil.sendCreated(exchange,
                        "User created. Email queued for sending.",
                        Map.of(
                                "userId", userInfo.userId(),
                                "uuid", userInfo.uuid(),
                                "userCreationStatus", "QUEUED",
                                "tokenGenerationStatus", "QUEUED",
                                "emailStatus", "QUEUED"
                        )
                );

            } catch (Exception e) {
                logger.error("Failed to create new user: {}", e.getMessage(), e);
                ResponseUtil.sendError(exchange, StatusCodes.INTERNAL_SERVER_ERROR, e.getMessage());
            }

        } catch (Exception ex) {
            logger.error("Invalid input for new user: {}", ex.getMessage(), ex);
            ResponseUtil.sendError(exchange, StatusCodes.BAD_REQUEST, "Invalid input: " + ex.getMessage());
        }
    }

    private boolean emailExists(Connection conn, String email) throws Exception {
        String sql = "SELECT 1 FROM users WHERE user_email = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int fetchRoleId(Connection conn, String roleName) throws Exception {
        String query = "SELECT role_id FROM roles WHERE UPPER(role_name) = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, roleName != null ? roleName.toUpperCase() : "VIEWER");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int roleId = rs.getInt("role_id");
                    logger.info("Role '{}' fetched with ID {}", roleName, roleId);
                    return roleId;
                }
            }
        }
        return -1;
    }

    private UserInfo insertUser(Connection conn, String firstName, String lastName, String email, int roleId, Integer companyId) throws Exception {
        String sql = """
                INSERT INTO users (first_name, last_name, user_email, password_hash, role_id, is_active, company_id)
                VALUES (?, ?, ?, '', ?, false, ?)
                RETURNING user_id, uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setString(3, email);
            ps.setInt(4, roleId);
            if (companyId != null) ps.setInt(5, companyId);
            else ps.setNull(5, java.sql.Types.INTEGER);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new RuntimeException("Failed to insert user");
                long userId = rs.getLong("user_id");
                String uuid = rs.getString("uuid");
                logger.info("Created user {} with ID {}", email, userId);
                return new UserInfo(userId, uuid);
            }
        }
    }

    private void insertPrimaryContact(Connection conn, long userId, String email) throws Exception {
        String sql = """
                INSERT INTO user_contacts (user_id, type, value, verified, is_primary)
                VALUES (?, 'email', ?, false, true)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    private String createOneTimeToken(Connection conn, long userId) throws Exception {
        String token = PasswordUtil.generateToken();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        String sql = """
                INSERT INTO user_password_tokens (user_id, token, expires_at)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, token);
            ps.setTimestamp(3, java.sql.Timestamp.from(expiresAt));
            ps.executeUpdate();
        }
        return token;
    }

    private void insertEvent(Connection conn, long userId, String email, String oneTimeLink) throws Exception {
        Map<String, Object> eventPayload = Map.of(
                "userId", userId,
                "email", email,
                "passwordResetLink", oneTimeLink,
                "userCreationStatus", "QUEUED",
                "tokenStatus", "QUEUED",
                "emailStatus", "QUEUED",
                "message", "User created, password token generated, email queued for sending"
        );
        String payloadJson = JsonUtil.mapper().writeValueAsString(eventPayload);

        String sql = """
                INSERT INTO event_outbox (event_type, payload, status)
                VALUES ('USER_CREATED', ?::jsonb, 'PENDING')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, payloadJson);
            ps.executeUpdate();
        }
    }

    private record UserInfo(long userId, String uuid) {}
}
