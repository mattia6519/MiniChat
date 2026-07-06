package com.example.minichat;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoUtils {
    private static final String PREFIX = "ENC3:";
    private static final int ITERATIONS = 600000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object CACHE_LOCK = new Object();
    private static final HashMap<String, SecretKeySpec> CHAT_KEYS = new HashMap<>();

    private CryptoUtils() {
    }

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static boolean unlockChatKey(String cacheId, String password, int myId, int contactId) {
        if (password == null || password.isEmpty() || myId <= 0 || contactId <= 0) {
            clearChatKey(cacheId);
            return false;
        }
        try {
            SecretKeySpec key = deriveKey(password, chatSalt(myId, contactId));
            synchronized (CACHE_LOCK) {
                CHAT_KEYS.put(cacheId, key);
            }
            return true;
        } catch (Exception e) {
            clearChatKey(cacheId);
            return false;
        }
    }

    static void clearChatKey(String cacheId) {
        synchronized (CACHE_LOCK) {
            CHAT_KEYS.remove(cacheId);
        }
    }

    static String encryptWithCachedKey(String plainText, String cacheId) throws Exception {
        SecretKeySpec key = getCachedKey(cacheId);
        if (key == null) {
            return plainText;
        }

        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return PREFIX + b64(iv) + ":" + b64(encrypted);
    }

    static String decryptForDisplay(String value, String cacheId) {
        if (!isEncrypted(value)) {
            return value;
        }
        SecretKeySpec key = getCachedKey(cacheId);
        if (key == null) {
            return "[messaggio criptato]";
        }
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                return "[messaggio criptato]";
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[password non valida]";
        }
    }

    private static SecretKeySpec getCachedKey(String cacheId) {
        synchronized (CACHE_LOCK) {
            return CHAT_KEYS.get(cacheId);
        }
    }

    private static SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        byte[] key;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] chatSalt(int firstUserId, int secondUserId) throws Exception {
        int a = Math.min(firstUserId, secondUserId);
        int b = Math.max(firstUserId, secondUserId);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(("MiniChat:chat-salt:v1:" + a + ":" + b).getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }
}
