package org.skypulse.handlers.auth;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.skypulse.config.database.JdbcUtils;
import org.skypulse.config.database.dtos.UserContext;
import org.skypulse.utils.HttpRequestUtil;
import org.skypulse.utils.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;

public class UpdateUserProfile implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(UpdateUserProfile.class);

    private static final Set<String> ALLOWED_ALERT_CHANNELS =
            Set.of("EMAIL", "SMS", "TELEGRAM");

    private final GetUserProfileHandler getUserProfileHandler = new GetUserProfileHandler();

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        UserContext ctx = exchange.getAttachment(UserContext.ATTACHMENT_KEY);
        if (ctx == null) {
            ResponseUtil.sendError(exchange, 401, "Unauthorized");
            return;
        }

        Map<String, Object> body = HttpRequestUtil.parseJson(exchange);
        if (body == null) {
            ResponseUtil.sendError(exchange, 400, "Invalid or empty JSON body");
            return;
        }

        Long userId = HttpRequestUtil.getLong(body, "user_id");
        if (userId == null) {
            ResponseUtil.sendError(exchange, 400, "Missing required field: user_id");
            return;
        }

        String firstName = HttpRequestUtil.getString(body, "first_name");
        String lastName = HttpRequestUtil.getString(body, "last_name");

        Map<String, Object> prefs = HttpRequestUtil.getMap(body, "user_preferences");

        String alertChannel = null;
        String language = null;
        String timezone = null;
        Boolean receiveWeeklyReports = null;

        if (prefs != null) {
            alertChannel = (String) prefs.get("alert_channel");
            language = (String) prefs.get("language");
            timezone = (String) prefs.get("timezone");
            receiveWeeklyReports = (Boolean) prefs.get("receive_weekly_reports");

            if (alertChannel != null && !ALLOWED_ALERT_CHANNELS.contains(alertChannel)) {
                ResponseUtil.sendError(
                        exchange,
                        400,
                        "Invalid alert_channel. Allowed values: EMAIL, SMS, TELEGRAM"
                );
                return;
            }
        }

        try (Connection conn = JdbcUtils.getConnection()) {

            /* ---------- Update users table ---------- */
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                    UPDATE users
                    SET first_name = COALESCE(?, first_name),
                        last_name  = COALESCE(?, last_name)
                    WHERE user_id = ?
                    """
            )) {
                ps.setString(1, firstName);
                ps.setString(2, lastName);
                ps.setLong(3, userId);

                if (ps.executeUpdate() == 0) {
                    ResponseUtil.sendError(exchange, 404, "User not found");
                    return;
                }
            }

            /* ---------- UPSERT user_preferences ---------- */
            if (prefs != null) {
                try (PreparedStatement psPrefs = conn.prepareStatement(
                        """
                        INSERT INTO user_preferences (
                            user_id,
                            alert_channel,
                            receive_weekly_reports,
                            language,
                            timezone
                        )
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE
                        SET alert_channel = COALESCE(EXCLUDED.alert_channel, user_preferences.alert_channel),
                            receive_weekly_reports = COALESCE(EXCLUDED.receive_weekly_reports, user_preferences.receive_weekly_reports),
                            language = COALESCE(EXCLUDED.language, user_preferences.language),
                            timezone = COALESCE(EXCLUDED.timezone, user_preferences.timezone)
                        """
                )) {
                    psPrefs.setLong(1, userId);
                    psPrefs.setString(2, alertChannel);

                    if (receiveWeeklyReports != null) {
                        psPrefs.setBoolean(3, receiveWeeklyReports);
                    } else {
                        psPrefs.setNull(3, java.sql.Types.BOOLEAN);
                    }

                    psPrefs.setString(4, language);
                    psPrefs.setString(5, timezone);

                    psPrefs.executeUpdate();
                }
            }

            Map<String, Object> updatedProfile =
                    getUserProfileHandler.getUserProfile(userId);

            ResponseUtil.sendSuccess(
                    exchange,
                    "User profile updated successfully",
                    updatedProfile
            );

        } catch (Exception e) {
            logger.error("Failed to update user profile", e);
            ResponseUtil.sendError(exchange, 500, "Failed to update user profile");
        }
    }
}


/*
{
  "data": {
    "user_id": 12,
    "uuid": "7470ede9-8abb-43ec-883e-0cc6f29aa48f",
    "first_name": "John",
    "last_name": "Doe",
    "email": "john@gmail.com",
    "role_name": "ADMIN",
    "company_name": "SkyPulse Inc.",
    "user_contacts": [
      {
        "contact_type": "EMAIL",
        "value": "john@gmail.com",
        "verified": false,
        "is_primary": true
      }
    ],
    "user_preferences": {
      "alert_channel": "EMAIL",
      "receive_weekly_reports": true,
      "language": "en",
      "timezone": "UTC",
      "dashboard_layout": {}
    }
  },
  "message": "User profile updated successfully"
}

* */