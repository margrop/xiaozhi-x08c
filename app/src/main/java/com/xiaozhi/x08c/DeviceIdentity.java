package com.xiaozhi.x08c;

import android.content.Context;
import android.provider.Settings;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Creates and persists the device identifier without requiring extra permissions. */
public final class DeviceIdentity {
    private DeviceIdentity() {
    }

    public static String getDeviceId(Context context) {
        SettingsStore store = new SettingsStore(context);
        String deviceId = store.getDeviceId();
        if (isUsablePersistedId(deviceId)) {
            return deviceId;
        }

        deviceId = stableHashId(readAndroidId(context));
        if (isEmpty(deviceId)) {
            deviceId = readWlan0Mac();
        }

        // ANDROID_ID can be unavailable on some devices. Reuse a saved value, or
        // create and persist one final installation-stable fallback.
        if (isEmpty(deviceId)) {
            deviceId = stableHashId(UUID.randomUUID().toString());
        }

        if (!isEmpty(deviceId)) {
            store.save(store.getServerUrl(), store.getToken(), deviceId);
        }
        return deviceId != null ? deviceId : "";
    }

    private static boolean isUsablePersistedId(String value) {
        return !isEmpty(value);
    }

    private static String readWlan0Mac() {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName("wlan0");
            if (networkInterface == null) {
                return "";
            }
            return formatBytes(networkInterface.getHardwareAddress());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readAndroidId(Context context) {
        try {
            return Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String stableHashId(String value) {
        if (isEmpty(value)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return formatBytes(hash);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Formats the first six bytes as exactly twelve lower-case hex digits with colons. */
    private static String formatBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 6) {
            return "";
        }
        StringBuilder result = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                result.append(':');
            }
            result.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return result.toString();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
