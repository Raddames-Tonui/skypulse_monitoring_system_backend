package org.skypulse.config.security;

import org.mindrot.jbcrypt.BCrypt;


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
}
