package org.skypulse.config.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Security;

public class CryptoInit {
    private static final Logger logger = LoggerFactory.getLogger(CryptoInit.class);
    private CryptoInit() {}

    public static void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.info("BouncyCastle provider registered.");
        }
    }
}
