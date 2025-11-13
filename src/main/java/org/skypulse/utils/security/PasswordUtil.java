package org.skypulse.utils.security;

import org.mindrot.jbcrypt.BCrypt;

public class PasswordUtil {

    private static final int WORK_FACTOR = 12;

    public static String hashPassword(String plainPassword){
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(WORK_FACTOR));
    }

    public static boolean verifyPassword(String plainPassword, String hashedPassword){
        if (plainPassword == null || hashedPassword == null){
            return false;
        }
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}
