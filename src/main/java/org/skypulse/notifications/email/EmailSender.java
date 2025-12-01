package org.skypulse.notifications.email;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.skypulse.notifications.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class EmailSender implements NotificationSender {

    private static final Logger logger = LoggerFactory.getLogger(EmailSender.class);

    // SMTP Configuration
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final int SMTP_PORT = 587;
    private static final String USERNAME = "raddamestonui48@gmail.com";
    private static final String PASSWORD = "vnfo ksqp tiou byht";
    private static final String FROM_ADDRESS = "raddamestonui48@gmail.com";
    private static final String FROM_NAME = "Skypulse Test";

    private static final boolean USE_TLS = true;
    private static final int TIMEOUT_MS = 50000;

    private final Session session;

    public EmailSender() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(USE_TLS));
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.debug", "true");

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USERNAME, PASSWORD);
            }
        });

        this.session.setDebug(true);
        logger.info("[ EmailSender initialized for host {}:{}]", SMTP_HOST, SMTP_PORT);
    }

    @Override
    public boolean send(String channelCode, String destination, String subject, String message, Map<String, String> inlineImages) {
        if (!"EMAIL".equalsIgnoreCase(channelCode)) return false;

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(FROM_ADDRESS, FROM_NAME));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destination));
            msg.setSubject(subject);

            Multipart multipart = EmailHelper.buildHtmlWithInlineImages(message, inlineImages);
            msg.setContent(multipart);
            msg.saveChanges();

            Transport.send(msg);
            logger.info("Email sent successfully to {}", destination);
            return true;

        } catch (MessagingException e) {
            logger.error("Failed to send email to {} via SMTP host {}:{} - {}", destination, SMTP_HOST, SMTP_PORT, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            logger.error("Unexpected error while sending email to {}: {}", destination, e.getMessage(), e);
            return false;
        }
    }
}
