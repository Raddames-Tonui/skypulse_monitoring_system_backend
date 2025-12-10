package org.skypulse.notifications.telegram;

import org.jetbrains.annotations.NotNull;
import org.skypulse.notifications.NotificationSender;
import org.skypulse.notifications.RecipientResolver.Recipient;
import org.skypulse.config.utils.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Telegram notification sender.
 * Sends messages to users/groups via a bot using chat IDs.
 * Supports HTML or Markdown parse modes and automatic retries.
 */
public class TelegramSender implements NotificationSender {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSender.class);

    private final XmlConfiguration.Notification.Telegram config;

    public TelegramSender(XmlConfiguration.Notification.Telegram config) {
        this.config = config;
        if (!config.enabled) {
            logger.info("[TelegramSender] Disabled in configuration.");
        } else {
            logger.info("[TelegramSender] Initialized for botToken=****, defaultChatId={}", config.defaultChatId);
        }
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        if (!"TELEGRAM".equalsIgnoreCase(channelCode) || !config.enabled) return false;

        String chatId = (destination != null && !destination.isBlank()) ? destination : config.defaultChatId;
        if (chatId == null || chatId.isBlank()) {
            logger.warn("No Telegram chat ID provided. Cannot send message.");
            return false;
        }

        // Telegram combines subject + message
        String fullMessage = (subject != null && !subject.isBlank()) ? subject + "\n\n" + message : message;

        boolean sent = false;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= config.sendRetryAttempts && !sent; attempt++) {
            try {
                sent = sendMessage(chatId, fullMessage);
            } catch (Exception e) {
                lastEx = e;
                logger.warn("Telegram send attempt {}/{} failed for chatId {}: {}", attempt, config.sendRetryAttempts, chatId, e.getMessage());
                try { Thread.sleep(config.connectionTimeout); } catch (InterruptedException ignored) {}
            }
        }

        if (!sent) {
            logger.error("Failed to send Telegram message to chatId {} after {} attempts", chatId, config.sendRetryAttempts, lastEx);
        } else {
            logger.info("Telegram message sent successfully to chatId {}", chatId);
        }

        return sent;
    }

    /**
     * Sends a message via Telegram Bot API.
     *
     * @param chatId  Destination chat ID
     * @param message Text to send
     * @return true if successfully sent
     * @throws Exception on HTTP/connection error
     */
    private boolean sendMessage(String chatId, String message) throws Exception {
        String apiUrl = config.apiUrl.endsWith("/") ? config.apiUrl : config.apiUrl + "/";
        String urlString = apiUrl + "bot" + config.botToken + "/sendMessage";

        JSONObject payload = new JSONObject();
        payload.put("chat_id", chatId);
        payload.put("text", message);
        payload.put("parse_mode", config.parseMode != null ? config.parseMode.toUpperCase() : "HTML");

        byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(config.connectionTimeout);
        conn.setReadTimeout(config.connectionTimeout);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        return responseCode >= 200 && responseCode < 300;
    }
}
