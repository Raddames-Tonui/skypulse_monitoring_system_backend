package org.skypulse.notifications.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sends notifications via Telegram bot.
 */
public class TelegramSender implements NotificationSender {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSender.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final XmlConfiguration.Notification.Telegram config;

    public TelegramSender(XmlConfiguration.Notification.Telegram config) {
        this.config = config;
        logger.info("[TelegramSender initialized, bot enabled={}]", config.enabled);
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        if (!"TELEGRAM".equalsIgnoreCase(channelCode) || !config.enabled) return false;

        String combinedMessage = combineSubjectAndBody(subject, message);
        int attempts = 0;
        boolean sent = false;

        while (attempts < config.sendRetryAttempts && !sent) {
            attempts++;
            try {
                sent = sendMessage(destination, combinedMessage);
            } catch (Exception e) {
                logger.warn("Telegram send attempt {} failed to {}: {}", attempts, destination, e.getMessage());
                try {
                    Thread.sleep(config.connectionTimeout);
                } catch (InterruptedException ignored) {}
            }
        }

        if (!sent) logger.error("Telegram message could not be sent to {} after {} attempts", destination, attempts);
        return sent;
    }

    private String combineSubjectAndBody(String subject, String body) {
        StringBuilder sb = new StringBuilder();
        if (subject != null && !subject.isBlank()) sb.append(subject).append("\n\n");
        sb.append(body != null ? body : "");
        return sb.toString();
    }

    private boolean sendMessage(String chatId, String text) throws Exception {
        URL url = new URL(String.format("%s/bot%s/sendMessage", config.apiUrl, config.botToken));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(config.connectionTimeout);
        conn.setReadTimeout(config.connectionTimeout);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        String payloadJson = String.format("""
            {"chat_id": "%s", "text": "%s", "parse_mode": "%s"}
            """, chatId, text, config.parseMode);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream is = responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        JsonNode json = mapper.readTree(response.toString());
        boolean success = json.path("ok").asBoolean(false);

        if (success) logger.info("Telegram message sent to {} successfully", chatId);
        else logger.error("Telegram message failed to {}: {}", chatId, json.toString());

        return success;
    }
}
