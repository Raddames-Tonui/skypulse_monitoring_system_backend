package org.skypulse.notifications.email;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.File;
import java.util.Map;

public class EmailHelper {

    /**
     * Converts HTML content into a MimeMultipart with inline images.
     * @param htmlContent Rendered HTML content (May contain src="cid:imageId")
     * @param inlineImages Map of imageId -> file path
     * @return Multipart ready to set in MimeMessage
     * @throws MessagingException
     */
    public static Multipart buildHtmlWithInlineImages(String htmlContent, Map<String, String> inlineImages) throws MessagingException {
        // 1. HTML body part
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlContent, "text/html; charset=UTF-8");

        // 2. Multipart container
        MimeMultipart multipart = new MimeMultipart("related");
        multipart.addBodyPart(htmlPart);

        // 3. Add inline images
        if (inlineImages != null) {
            for (Map.Entry<String, String> entry : inlineImages.entrySet()) {
                String cid = entry.getKey();
                String path = entry.getValue();

                File file = new File(path);
                if (!file.exists()) continue;

                MimeBodyPart imagePart = new MimeBodyPart();
                DataSource fds = new FileDataSource(file);
                imagePart.setDataHandler(new DataHandler(fds));
                imagePart.setHeader("Content-ID", "<" + cid + ">");
                imagePart.setDisposition(MimeBodyPart.INLINE);
                multipart.addBodyPart(imagePart);
            }
        }

        return multipart;
    }
}
