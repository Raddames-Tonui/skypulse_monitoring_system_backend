package org.skypulse.config;

import org.skypulse.config.utils.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * - encrypt IN-PLACE (same file)
 * - decrypt IN-MEMORY (no temp files)
 */
public class ConfigEncryptor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigEncryptor.class);

    // --- Crypto params ---
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();
    private static final String PREFIX = "v1:gcm:pbkdf2:";  // v1:gcm:pbkdf2:<salt>:<iv>:<ciphertext>

    /**
     * CLI (developer use only):
     *  INTELLIJ (in arguments)
     *      encrypt config.xml
     *      decrypt config.xml
     * Uses CONFIG_MASTER_KEY from environment (via KeyProvider).
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            logger.debug("Usage: java org.skypulse.config.ConfigEncryptor <encrypt|decrypt> <file>");
            System.exit(1);
        }
        String mode = args[0];
        String file = args[1];

        String password = KeyProvider.getEncryptionKey();
        Document doc = readXml(file);

        if ("encrypt".equalsIgnoreCase(mode)) {
            transformValues(doc, password, true);
            writeXml(doc, file); // IN-PLACE
            logger.debug("Encrypted in-place → {}", file);
        } else if ("decrypt".equalsIgnoreCase(mode)) {
            // For debugging only. In production, use decryptInMemory(...)
            transformValues(doc, password, false);
            writeXml(doc, file); // IN-PLACE (not recommended in prod)
            logger.debug("Decrypted in-place → {}", file);
        } else {
            logger.warn("First arg must be 'encrypt' or 'decrypt'");
            System.exit(2);
        }
    }

    /** Decrypts the given encrypted XML file entirely in memory; never writes plaintext to disk. */
    public static Document decryptInMemory(String encryptedFilePath, String password) throws Exception {
        Document doc = readXml(encryptedFilePath);
        transformValues(doc, password, false); // ENCRYPTED -> TEXT
        return doc; // plaintext only in memory
    }

    /** Core transformation: encrypt @mode="TEXT" or decrypt @mode="ENCRYPTED". */
    private static void transformValues(Document doc, String password, boolean encrypt) throws Exception {
        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodes = (NodeList) xPath.evaluate("//*[@mode]", doc, XPathConstants.NODESET);

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String mode = el.getAttribute("mode").trim();
            String value = textContentTrimmed(el);

            if (encrypt && "TEXT".equalsIgnoreCase(mode)) {
                if (!value.isEmpty()) {
                    String sealed = seal(password, value);
                    setText(el, sealed);
                    el.setAttribute("mode", "ENCRYPTED");
                }
            } else if (!encrypt && "ENCRYPTED".equalsIgnoreCase(mode)) {
                if (!value.isEmpty()) {
                    String opened = open(password, value);
                    setText(el, opened);
                    el.setAttribute("mode", "TEXT");
                }
            }
        }
    }

    private static String textContentTrimmed(Element el) {
        String s = el.getTextContent();
        return s == null ? "" : s.trim();
    }

    private static void setText(Element el, String newText) {
        while (el.hasChildNodes()) el.removeChild(el.getFirstChild());
        el.appendChild(el.getOwnerDocument().createTextNode(newText));
    }

    // --- XML IO (secure) ---
    private static Document readXml(String path) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        // Secure parsing
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        try (InputStream is = new FileInputStream(path)) {
            Document doc = db.parse(is);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }

    private static void writeXml(Document doc, String path) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty("indent", "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        try (OutputStream os = new FileOutputStream(path)) {
            t.transform(new DOMSource(doc), new StreamResult(os));
        }
    }

    // --- Crypto helpers ---
    private static String seal(String password, String plaintext) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        SecretKey key = deriveKey(password, salt);

        byte[] iv = randomBytes(IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return PREFIX + b64(salt) + ":" + b64(iv) + ":" + b64(ct);
    }

    private static String open(String password, String payload) throws Exception {
        if (!payload.startsWith(PREFIX)) throw new IllegalArgumentException("Unsupported payload format");
        String[] parts = payload.substring(PREFIX.length()).split(":");
        if (parts.length != 3) throw new IllegalArgumentException("Malformed payload");

        byte[] salt = b64d(parts[0]);
        byte[] iv   = b64d(parts[1]);
        byte[] ct   = b64d(parts[2]);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        byte[] keyBytes = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static String b64(byte[] in) {
        return Base64.getEncoder().withoutPadding().encodeToString(in);
    }
    private static byte[] b64d(String s) {
        return Base64.getDecoder().decode(s);
    }
}
