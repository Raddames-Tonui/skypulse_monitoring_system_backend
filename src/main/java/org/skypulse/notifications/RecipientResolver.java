package org.skypulse.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Resolve recipients by event type.
 *
 * Channel      Source Table                         Notes
 * -----------  -----------------------------------  ------------------------------------
 * EMAIL        `user_contacts` OR users.user_email  Users get emails directly
 * SMS          `user_contacts`                      Phone numbers
 * TELEGRAM     `contact_group_contacts`             Must use group-level chat IDs
 * SLACK/TEAMS  `contact_group_contacts`             Webhooks belong to groups, not users
 */
public class RecipientResolver {

    private static final Logger logger = LoggerFactory.getLogger(RecipientResolver.class);

    public record Recipient(Long userId, Long contactGroupId, String type, String value) {}

    public static List<Recipient> resolveRecipients(Connection conn,
                                                    String eventType,
                                                    long serviceId,
                                                    Object payloadUserId) throws SQLException {
        return switch (eventType) {
            case "USER_CREATED", "RESET_PASSWORD" ->
                    resolveToUserPrimaryContacts(conn, payloadUserId);

            case "UPTIME_REPORTS", "SERVICE_DOWN", "SERVICE_RECOVERED", "SSL_EXPIRING" ->
                    resolveServiceNotificationContacts(conn, serviceId);

            default ->
                    resolveServiceNotificationContacts(conn, serviceId);
        };
    }

    // USER_CREATED & RESET_PASSWORD use users.user_email for system events
    private static List<Recipient> resolveToUserPrimaryContacts(Connection conn, Object payloadUserId)
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
                return List.of(new Recipient(userId, null, "EMAIL", email));
            }
        }
    }

    // All service notifications (EMAIL/SMS per user, TELEGRAM/SLACK/TEAMS per group)
    private static List<Recipient> resolveServiceNotificationContacts(Connection conn, long serviceId)
            throws SQLException {

        List<Recipient> recipients = new ArrayList<>();

        // 1. USER-LEVEL CHANNELS: EMAIL / SMS
        String sqlUserContacts = """
            SELECT DISTINCT uc.user_id,
                            UPPER(uc.type) AS type,
                            uc.value
            FROM monitored_services_contact_groups mscg
            JOIN contact_group_members cgm ON cgm.contact_group_id = mscg.contact_group_id
            JOIN user_contacts uc ON uc.user_id = cgm.user_id
            WHERE mscg.monitored_service_id = ?
              AND uc.type IN ('EMAIL', 'SMS')
        """;
        addUserRecipients(conn, serviceId, recipients, sqlUserContacts);

        // 2. GROUP-LEVEL CHANNELS: TELEGRAM / SLACK / TEAMS
        String sqlGroupContacts = """
            SELECT DISTINCT cgc.contact_group_id, cgc.type, cgc.value
            FROM monitored_services_contact_groups mscg
            JOIN contact_group_contacts cgc ON cgc.contact_group_id = mscg.contact_group_id
            WHERE mscg.monitored_service_id = ?
              AND cgc.type IN ('TELEGRAM', 'SLACK', 'TEAMS')
        """;
        addGroupRecipients(conn, serviceId, recipients, sqlGroupContacts);

        return recipients;
    }

    // Helper for user-level channels (EMAIL/SMS)
    private static void addUserRecipients(Connection conn, long serviceId, List<Recipient> recipients, String sql)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    recipients.add(new Recipient(
                            rs.getLong("user_id"),
                            null,
                            rs.getString("type"),
                            rs.getString("value")
                    ));
                }
            }
        }
    }

    // Helper for group-level channels (TELEGRAM/SLACK/TEAMS)
    private static void addGroupRecipients(Connection conn, long serviceId, List<Recipient> recipients, String sql)
            throws SQLException {
        Set<String> seen = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long groupId = rs.getLong("contact_group_id");
                    String type = rs.getString("type").toUpperCase();
                    String value = rs.getString("value");
                    String key = type + ":" + value;

                    if (!seen.contains(key)) {
                        seen.add(key);
                        recipients.add(new Recipient(0L, groupId, type, value));
                    }
                }
            }
        }
    }
}
