package org.skypulse.config.security;

import org.jetbrains.annotations.NotNull;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

public class SecureFieldCrypto {

    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String PREFIX = "v1:gcm:pbkdf2:";
    private static final SecureRandom RNG = new SecureRandom();



    public static String encrypt(String plaintext) throws Exception {
        if (plaintext == null) return null;

        String password = KeyProvider.getEncryptionKey();

        return getString(plaintext, password, SALT_BYTES, IV_BYTES, GCM_TAG_BITS, PREFIX);
    }

    @NotNull
    public static String getString(String plaintext, String password, int saltBytes, int ivBytes, int gcmTagBits, String prefix) throws Exception {
        byte[] salt = randomBytes(saltBytes);
        SecretKey key = deriveKey(password, salt);

        byte[] iv = randomBytes(ivBytes);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(gcmTagBits, iv));

        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return prefix + b64(salt) + ":" + b64(iv) + ":" + b64(ct);
    }

    public static String decrypt(String encrypted) throws Exception {
        if (encrypted == null) return null;

        // Legacy plaintext data is returned as-is
        if (!encrypted.startsWith(PREFIX)) {
            return encrypted;
        }

        String password = KeyProvider.getEncryptionKey();

        String[] parts = encrypted.substring(PREFIX.length()).split(":");
        byte[] salt = b64d(parts[0]);
        byte[] iv = b64d(parts[1]);
        byte[] ct = b64d(parts[2]);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }

    public static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        byte[] keyBytes = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String b64(byte[] in) {
        return Base64.getEncoder().withoutPadding().encodeToString(in);
    }

    public static byte[] b64d(String s) {
        return Base64.getDecoder().decode(s);
    }
}
