package org.skypulse.notifications;

import java.util.Map;

public interface NotificationSender {
    /**
     * @param channelCode  "EMAIL", "SMS", "TELEGRAM"
     * @param destination  email address, phone number, or telegram handle
     * @param subject      message subject (used for email)
     * @param message      message body
     * @return true if sent successfully
     */
    boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages);
}