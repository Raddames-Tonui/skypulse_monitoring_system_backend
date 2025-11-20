package org.skypulse.config;

import org.skypulse.config.security.KeyProvider;
import org.skypulse.config.utils.XmlConfiguration;
import org.skypulse.utils.XmlUtil;
import org.w3c.dom.Document;


public class ConfigLoader {
    /**
     * Loads the encrypted XML, decrypts IN MEMORY using KeyProvider,
     * and returns a fully-typed XmlConfiguration object.
     */
    public static XmlConfiguration loadConfig(String encryptedXMLPath) {
        try{
            String key = KeyProvider.getEncryptionKey();
            Document decryptedDoc = ConfigEncryptor.decryptInMemory(encryptedXMLPath, key);
            return XmlUtil.unmarshal(decryptedDoc, XmlConfiguration.class);
        }catch(Exception e){
            throw new IllegalStateException("Failed to load config file!" + e.getMessage(), e);
        }
    }

}
