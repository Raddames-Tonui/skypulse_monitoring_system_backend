package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve recipients by event type.
 */
public class RecipientResolver {

    private static final Logger logger = LoggerFactory.getLogger(RecipientResolver.class);

    public record Recipient(long userId, String type, String value) {}


    public static List<Recipient> resolveRecipients(Connection conn,
                                                    String eventType,
                                                    long serviceId,
                                                    Object payloadUserId) throws SQLException {

        return switch (eventType) {

            case "USER_CREATED", "RESET_PASSWORD" ->
                    resolveUserCreatedOrReset(conn, payloadUserId);

            default ->
                    resolveServiceNotificationContacts(conn, serviceId);
        };
    }

    // USER_CREATED & RESET_PASSWORD use users.user_email
    private static List<Recipient> resolveUserCreatedOrReset(Connection conn, Object payloadUserId)
            throws SQLException {

        if (payloadUserId == null) {
            throw new SQLException("Missing userId in payload for USER_CREATED/RESET_PASSWORD");
        }

        long userId = Long.parseLong(payloadUserId.toString());

        String sql = """
            SELECT user_id, user_email
            FROM users
            WHERE user_id = ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();

                String email = rs.getString("user_email");

                List<Recipient> list = new ArrayList<>();
                list.add(new Recipient(userId, "EMAIL", email));
                return list;
            }
        }
    }

    // All service notifications
    private static List<Recipient> resolveServiceNotificationContacts(Connection conn, long serviceId)
            throws SQLException {

        List<Recipient> list = new ArrayList<>();

        String sql = """
            SELECT uc.user_id, UPPER(uc.type) AS type, uc.value
            FROM monitored_services_contact_groups mscg
            JOIN contact_group_members cgm ON cgm.contact_group_id = mscg.contact_group_id
            JOIN user_contacts uc ON uc.user_id = cgm.user_id
            WHERE mscg.monitored_service_id = ?
            ORDER BY uc.is_primary DESC
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(
                            new Recipient(
                                    rs.getLong("user_id"),
                                    rs.getString("type"),
                                    rs.getString("value")
                            )
                    );
                }
            }
        }

        return list;
    }
}
