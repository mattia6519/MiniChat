package com.example.minichat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

final class ChatPasswordStore {
    private static final String FILE = "minichat_chat_passwords";
    private static final String FALLBACK_PREFIX = "PLAIN1:";
    private static final Object SESSION_LOCK = new Object();
    private static final HashMap<String, String> SESSION_PASSWORDS = new HashMap<>();

    private ChatPasswordStore() {
    }

    static String getPassword(Context context, String ownerPhone, int contactId) {
        String itemKey = key(ownerPhone, contactId);
        String stored = prefs(context).getString(itemKey, "");
        if (stored.startsWith(FALLBACK_PREFIX)) {
            return stored.substring(FALLBACK_PREFIX.length());
        }
        if (!stored.isEmpty() && !AppSecureStore.isEncrypted(stored)) {
            String encrypted = AppSecureStore.encrypt(context, stored);
            if (!encrypted.isEmpty()) {
                prefs(context).edit().putString(itemKey, encrypted).commit();
            }
            return stored;
        }
        return AppSecureStore.decrypt(context, stored);
    }

    static String getSessionPassword(String ownerPhone, int contactId) {
        synchronized (SESSION_LOCK) {
            String value = SESSION_PASSWORDS.get(key(ownerPhone, contactId));
            return value == null ? "" : value;
        }
    }

    static void saveSessionPassword(String ownerPhone, int contactId, String password) {
        synchronized (SESSION_LOCK) {
            String itemKey = key(ownerPhone, contactId);
            if (password == null || password.isEmpty()) {
                SESSION_PASSWORDS.remove(itemKey);
            } else {
                SESSION_PASSWORDS.put(itemKey, password);
            }
        }
    }

    static boolean hasPersistentPassword(Context context, String ownerPhone, int contactId) {
        return !prefs(context).getString(key(ownerPhone, contactId), "").isEmpty();
    }

    static boolean savePassword(Context context, String ownerPhone, int contactId, String password) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (password == null || password.isEmpty()) {
            editor.remove(key(ownerPhone, contactId));
        } else {
            String encrypted = AppSecureStore.encrypt(context, password);
            if (encrypted.isEmpty()) {
                encrypted = FALLBACK_PREFIX + password;
            }
            editor.putString(key(ownerPhone, contactId), encrypted);
        }
        boolean saved = editor.commit();
        if (saved) {
            saveSessionPassword(ownerPhone, contactId, password);
        }
        return saved;
    }

    static boolean clearPersistentPassword(Context context, String ownerPhone, int contactId) {
        return prefs(context).edit().remove(key(ownerPhone, contactId)).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String key(String ownerPhone, int contactId) {
        return PhoneUtils.normalize(ownerPhone) + "_" + contactId;
    }
}
