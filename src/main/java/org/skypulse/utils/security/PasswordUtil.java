package org.skypulse.utils.security;

import org.mindrot.jbcrypt.BCrypt;

import java.util.Base64;
import java.util.Random;


public class PasswordUtil {
    private PasswordUtil(){}

    private static final int BCRYPT_COST= 12;

    public static String hashPassword(String plainPassword) {
        if (plainPassword == null) throw new IllegalArgumentException("Password cannot be null");
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_COST));
    }

    public static boolean verifyPassword(String plainPassword, String storedHash){
        if (plainPassword == null || storedHash == null) return false;
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (Exception e) {
            return false;
        }
    }


    public static boolean isValidEmail(String email) {
        if (email == null) return false;
        String regex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";
        return email.matches(regex);
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        new Random().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
