package org.skypulse.config;

import org.w3c.dom.Document;

import java.lang.module.Configuration;

public class ConfigLoader {
    /**
     * Loads the encrypted XML, decrypts IN MEMORY using KeyProvider,
     * and returns a fully-typed Configuration object.
     */

    public static Configuration loadConfig(String encryptedXMLPath) {
        try {
            String key = KeyProvider.getEncryptionKey();
            Document decryptedDoc = ConfigEncryptor.decryptInMemory(encryptedXMLPath, key);
            return XmlUtil.unmarshal(decryptedDoc, Configuration.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load config file!" + e.getMessage(), e);
        }
    }

}
