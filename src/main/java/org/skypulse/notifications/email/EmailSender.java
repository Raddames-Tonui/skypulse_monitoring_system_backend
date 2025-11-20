package org.skypulse.notifications.email;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.NotificationSender;

import java.util.Map;
import java.util.Properties;
public  class EmailSender implements NotificationSender {

    private final XmlConfiguration.Notification.Email config;
    private final Session session;

    public EmailSender(XmlConfiguration.Notification.Email config) {
        this.config = config;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.useTLS));
        props.put("mail.smtp.host", config.smtpHost);
        props.put("mail.smtp.port", String.valueOf(config.smtpPort));
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.connectionTimeout));
        props.put("mail.smtp.timeout", String.valueOf(config.connectionTimeout));

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username, config.password);
            }
        });
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        if (!"EMAIL".equals(channelCode)) return false;
        try {
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.fromAddress, config.fromName));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destination));
            msg.setSubject(subject);

            // Build multipart with inline images
            Multipart multipart = EmailHelper.buildHtmlWithInlineImages(message, inlineImages);
            msg.setContent(multipart);

            Transport.send(msg);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}

