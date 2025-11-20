package org.skypulse.notifications;

import java.util.HashMap;
import java.util.Map;

public class MultiChannelSender implements NotificationSender {

    private final Map<String, NotificationSender> senders = new HashMap<>();

    public void addSender(String channelCode, NotificationSender sender) {
        senders.put(channelCode, sender);
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        NotificationSender sender = senders.get(channelCode);
        if (sender == null) return false;
        return sender.send(channelCode, destination, subject, message, inlineImages);
    }
}
