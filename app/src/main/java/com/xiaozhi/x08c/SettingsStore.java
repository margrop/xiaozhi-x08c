package com.xiaozhi.x08c;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Persistent connection settings and the installation-stable client identifier. */
public final class SettingsStore {
    private static final String PREFERENCES_NAME = "xiaozhi_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_WAKE_ENABLED = "wake_enabled";
    private static final Object CLIENT_ID_LOCK = new Object();

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext != null ? applicationContext : context;
        preferences = safeContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return preferences.getString(KEY_SERVER_URL, "");
    }

    public String getToken() {
        return preferences.getString(KEY_TOKEN, "");
    }

    public String getDeviceId() {
        return preferences.getString(KEY_DEVICE_ID, "");
    }

    /** Wake-word detection enabled (default true). */
    public boolean isWakeEnabled() {
        return preferences.getBoolean(KEY_WAKE_ENABLED, true);
    }

    public void setWakeEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_WAKE_ENABLED, enabled).apply();
    }

    public String getClientId() {
        synchronized (CLIENT_ID_LOCK) {
            String clientId = preferences.getString(KEY_CLIENT_ID, "");
            if (clientId == null || clientId.trim().isEmpty()) {
                clientId = UUID.randomUUID().toString();
                preferences.edit().putString(KEY_CLIENT_ID, clientId).apply();
            }
            return clientId;
        }
    }

    public void save(String url, String token, String deviceId) {
        preferences.edit()
                .putString(KEY_SERVER_URL, valueOrEmpty(url))
                .putString(KEY_TOKEN, valueOrEmpty(token))
                .putString(KEY_DEVICE_ID, valueOrEmpty(deviceId))
                .apply();
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }
}
