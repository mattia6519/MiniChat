package com.example.minichat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AppSecureStore {
    private static final String PREFIX_V1 = "AST1:";
    private static final String PREFIX_V2 = "AST2:";
    private static final String KEY_ALIAS = "MiniChatLocalStorageKey";
    private static final String MATERIAL_PREFS = "minichat_secure_material";
    private static final String KEY_WRAPPED_DEK = "wrapped_dek";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object KEY_LOCK = new Object();
    private static SecretKeySpec cachedDataKey;

    private AppSecureStore() {
    }

    static void warmUp(Context context) {
        try {
            getOrCreateDataKey(context);
        } catch (Exception ignored) {
        }
    }

    static String encrypt(Context context, String plainText) {
        if (plainText == null) {
            plainText = "";
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateDataKey(context), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX_V2 + b64(iv) + ":" + b64(encrypted);
        } catch (Exception e) {
            return "";
        }
    }

    static String decrypt(Context context, String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith(PREFIX_V2)) {
            return decryptV2(context, value);
        }
        if (value.startsWith(PREFIX_V1)) {
            return decryptLegacyV1(context, value);
        }
        return value;
    }

    static boolean isEncrypted(String value) {
        return value != null && (value.startsWith(PREFIX_V1) || value.startsWith(PREFIX_V2));
    }

    static void resetDataKey(Context context) {
        synchronized (KEY_LOCK) {
            cachedDataKey = null;
            context.getApplicationContext()
                    .getSharedPreferences(MATERIAL_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_WRAPPED_DEK)
                    .commit();
        }
    }

    private static String decryptV2(Context context, String value) {
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                return "";
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateDataKey(context), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String decryptLegacyV1(Context context, String value) {
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                return "";
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKeySpec getOrCreateDataKey(Context context) throws Exception {
        synchronized (KEY_LOCK) {
            if (cachedDataKey != null) {
                return cachedDataKey;
            }

            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(MATERIAL_PREFS, Context.MODE_PRIVATE);
            String wrapped = prefs.getString(KEY_WRAPPED_DEK, "");
            if (wrapped == null || wrapped.isEmpty()) {
                byte[] rawKey = new byte[32];
                RANDOM.nextBytes(rawKey);
                String wrappedKey = wrapDataKey(rawKey);
                if (!prefs.edit().putString(KEY_WRAPPED_DEK, wrappedKey).commit()) {
                    throw new IllegalStateException("Unable to persist data key");
                }
                cachedDataKey = new SecretKeySpec(rawKey, "AES");
                return cachedDataKey;
            }

            byte[] rawKey = unwrapDataKey(wrapped);
            cachedDataKey = new SecretKeySpec(rawKey, "AES");
            return cachedDataKey;
        }
    }

    private static String wrapDataKey(byte[] rawKey) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(rawKey);
        return "WDEK1:" + b64(iv) + ":" + b64(encrypted);
    }

    private static byte[] unwrapDataKey(String wrapped) throws Exception {
        String[] parts = wrapped.split(":", 3);
        if (parts.length != 3 || !"WDEK1".equals(parts[0])) {
            throw new IllegalStateException("Invalid wrapped data key");
        }
        byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(parts[2], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey getOrCreateWrappingKey() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }

        byte[] fallback = "MiniChatLocalFallbackKey-32bytes!!".getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(fallback, 0, 32, "AES");
    }

    private static String b64(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }
}
