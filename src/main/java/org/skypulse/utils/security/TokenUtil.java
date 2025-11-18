package org.skypulse.utils.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token generator and hasher for refresh tokens.
 * - generateToken(): returns URL-safe token string (client)
 * - hashToken(): returns sha256 hex/base64 or similar for DB storage
 */
public class TokenUtil {
    private TokenUtil() {};

    private static final SecureRandom RNG = new SecureRandom();

    public static String generateToken(int byteLen) {
        byte[] bytes = new byte[byteLen];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String generateToken() {
        return generateToken(48);
    }

    public static String hashToken(String token) throws Exception{
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    }
}


/**
 * String token = TokenUtil.generateToken(); — return token to client as refresh token.
 * String stored = TokenUtil.hashToken(token); — store in DB.
 * On refresh call, hash incoming token and compare to DB hash.
 * */