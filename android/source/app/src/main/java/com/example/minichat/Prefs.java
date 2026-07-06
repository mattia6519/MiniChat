package com.example.minichat;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "minichat_settings";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USE_TLS = "use_tls";
    private static final String KEY_USE_CENTRAL_SERVER = "use_central_server";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_COUNTRY_CODE = "country_code";
    private static final String FALLBACK_PREFIX = "PLAIN1:";
    private static final String DEFAULT_HOST = "10.0.2.2";
    private static final int DEFAULT_PORT = 5555;
    private static final String DEFAULT_COUNTRY_CODE = "39";
    // Public builds intentionally do not embed the private central endpoint.
    // Private releases can inject the host here during their own build process.
    private static final String CENTRAL_HOST = "";
    private static final int CENTRAL_PORT = 443;

    private Prefs() {
    }

    static String getHost(Context context) {
        return useCentralServer(context) ? CENTRAL_HOST : getCustomHost(context);
    }

    static int getPort(Context context) {
        if (useCentralServer(context)) {
            return CENTRAL_PORT;
        }
        return getCustomPort(context);
    }

    static boolean useTls(Context context) {
        return useCentralServer(context) || customUseTls(context);
    }

    static boolean useCentralServer(Context context) {
        return hasCentralServer() && "1".equals(getPlainOrSecureString(context, KEY_USE_CENTRAL_SERVER, "0"));
    }

    static boolean hasCentralServer() {
        return !CENTRAL_HOST.isEmpty();
    }

    static String getCustomHost(Context context) {
        return getPlainOrSecureString(context, KEY_HOST, DEFAULT_HOST);
    }

    static int getCustomPort(Context context) {
        try {
            return Integer.parseInt(getPlainOrSecureString(context, KEY_PORT, String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    static boolean customUseTls(Context context) {
        return "1".equals(getPlainOrSecureString(context, KEY_USE_TLS, "0"));
    }

    static boolean saveServer(Context context, String host, int port, boolean useTls, boolean useCentralServer) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(KEY_HOST, host.trim());
        editor.putString(KEY_PORT, String.valueOf(port));
        editor.putString(KEY_USE_TLS, useTls ? "1" : "0");
        editor.putString(KEY_USE_CENTRAL_SERVER, useCentralServer ? "1" : "0");
        return editor.commit()
                && host.trim().equals(getCustomHost(context))
                && port == getCustomPort(context)
                && useTls == Prefs.customUseTls(context)
                && useCentralServer == Prefs.useCentralServer(context);
    }

    static String getUsername(Context context) {
        return getSecureString(context, KEY_USERNAME, "");
    }

    static void saveUsername(Context context, String username) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (!putSecureString(context, editor, KEY_USERNAME, username.trim())) {
            return;
        }
        editor.commit();
    }

    static String getPhone(Context context) {
        return getSecureString(context, KEY_PHONE, "");
    }

    static void savePhone(Context context, String phone) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (!putSecureString(context, editor, KEY_PHONE, phone.trim())) {
            return;
        }
        editor.commit();
    }

    static String getCountryCode(Context context) {
        return PhoneUtils.countryCodeDigits(getPlainOrSecureString(context, KEY_COUNTRY_CODE, DEFAULT_COUNTRY_CODE));
    }

    static void saveCountryCode(Context context, String countryCode) {
        String clean = PhoneUtils.countryCodeDigits(countryCode);
        if (clean.isEmpty()) {
            clean = DEFAULT_COUNTRY_CODE;
        }
        prefs(context).edit().putString(KEY_COUNTRY_CODE, clean).commit();
    }

    static String getPassword(Context context) {
        return getSecureString(context, KEY_PASSWORD, "");
    }

    static int getUserId(Context context) {
        try {
            return Integer.parseInt(getSecureString(context, KEY_USER_ID, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean hasSession(Context context) {
        return "1".equals(getSecureString(context, KEY_LOGGED_IN, "0"))
                && !getPhone(context).isEmpty()
                && !getPassword(context).isEmpty();
    }

    static boolean saveSession(Context context, String phone, String password, int userId) {
        SharedPreferences.Editor editor = prefs(context).edit();
        String normalizedPhone = PhoneUtils.normalize(phone);
        if (!putSecureString(context, editor, KEY_PHONE, normalizedPhone)
                || !putSecureString(context, editor, KEY_PASSWORD, password)
                || !putSecureString(context, editor, KEY_LOGGED_IN, "1")
                || !putSecureString(context, editor, KEY_USER_ID, String.valueOf(userId))) {
            return false;
        }
        return editor.commit()
                && normalizedPhone.equals(getPhone(context))
                && password.equals(getPassword(context))
                && hasSession(context);
    }

    static void clearSession(Context context) {
        prefs(context).edit()
                .remove(KEY_LOGGED_IN)
                .remove(KEY_PASSWORD)
                .remove(KEY_USER_ID)
                .commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String getSecureString(Context context, String key, String fallback) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(key)) {
            return fallback;
        }
        String stored;
        try {
            stored = preferences.getString(key, fallback);
        } catch (ClassCastException e) {
            if (KEY_PORT.equals(key)) {
                stored = String.valueOf(preferences.getInt(key, DEFAULT_PORT));
            } else if (KEY_LOGGED_IN.equals(key)) {
                stored = preferences.getBoolean(key, false) ? "1" : "0";
            } else {
                return fallback;
            }
        }
        if (stored == null) {
            return fallback;
        }
        if (stored.isEmpty()) {
            return fallback;
        }
        if (stored.startsWith(FALLBACK_PREFIX)) {
            return stored.substring(FALLBACK_PREFIX.length());
        }
        if (!AppSecureStore.isEncrypted(stored)) {
            SharedPreferences.Editor editor = preferences.edit();
            if (putSecureString(context, editor, key, stored)) {
                editor.commit();
            }
            return stored;
        }
        String decrypted = AppSecureStore.decrypt(context, stored);
        return decrypted.isEmpty() && !stored.isEmpty() && AppSecureStore.isEncrypted(stored) ? fallback : decrypted;
    }

    private static String getPlainOrSecureString(Context context, String key, String fallback) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(key)) {
            return fallback;
        }
        String stored;
        try {
            stored = preferences.getString(key, fallback);
        } catch (ClassCastException e) {
            if (KEY_PORT.equals(key)) {
                return String.valueOf(preferences.getInt(key, DEFAULT_PORT));
            }
            return fallback;
        }
        if (stored == null || stored.isEmpty()) {
            return fallback;
        }
        if (!AppSecureStore.isEncrypted(stored)) {
            return stored;
        }
        String decrypted = AppSecureStore.decrypt(context, stored);
        return decrypted.isEmpty() ? fallback : decrypted;
    }

    private static boolean putSecureString(Context context, SharedPreferences.Editor editor, String key, String value) {
        String cleanValue = value == null ? "" : value;
        String encrypted = AppSecureStore.encrypt(context, cleanValue);
        if (!cleanValue.isEmpty() && encrypted.isEmpty()) {
            editor.putString(key, FALLBACK_PREFIX + cleanValue);
            return true;
        }
        editor.putString(key, encrypted);
        return true;
    }

}
