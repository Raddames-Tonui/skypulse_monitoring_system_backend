package org.skypulse.notifications.email;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.jetbrains.annotations.NotNull;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.notifications.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class EmailSender implements NotificationSender {

    private static final Logger logger = LoggerFactory.getLogger(EmailSender.class);

    private final XmlConfiguration.Notification.Email config;
    private final Session session;

    public EmailSender(XmlConfiguration.Notification.Email config) {
        this.config = config;

        Properties props = getProperties(config);

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username, config.password);
            }
        });

        this.session.setDebug(true);
        logger.info("[---------- EmailSender initialized for host {}:{} ----------]", config.smtpHost, config.smtpPort);
    }

    @NotNull
    private static Properties getProperties(XmlConfiguration.Notification.Email config) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.useTLS));
        props.put("mail.smtp.host", config.smtpHost);
        props.put("mail.smtp.port", String.valueOf(config.smtpPort));
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.connectionTimeout));
        props.put("mail.smtp.timeout", String.valueOf(config.connectionTimeout));
        props.put("mail.smtp.writetimeout", String.valueOf(config.connectionTimeout));

        // Enable debug for SMTP session
        props.put("mail.debug", "true");
        return props;
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        if (!"EMAIL".equalsIgnoreCase(channelCode)) return false;

        try {
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.fromAddress, config.fromName));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destination));
            msg.setSubject(subject);

            Multipart multipart = EmailHelper.buildHtmlWithInlineImages(message, inlineImages);
            msg.setContent(multipart);

            Transport.send(msg);
            logger.info("Email sent successfully to {}", destination);
            return true;
        } catch (MessagingException e) {
            logger.error("Failed to send email to {} via SMTP host {}:{}", destination, config.smtpHost, config.smtpPort, e);
            return false;
        } catch (Exception e) {
            logger.error("Unexpected error while sending email to {}", destination, e);
            return false;
        }
    }
}