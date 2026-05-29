package com.riad.rrlkr.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Preference Manager - Handles encrypted storage of app preferences
 */
public class PreferenceManager {

    private static final String PREFS_NAME = "emi_locker_prefs";
    private static PreferenceManager instance;

    // Keys
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_ENROLLED = "enrolled";
    private static final String KEY_DEVICE_ADMIN = "device_admin";
    private static final String KEY_DEVICE_LOCKED = "device_locked";
    private static final String KEY_LOCK_MESSAGE = "lock_message";
    private static final String KEY_CONTACT_NUMBER = "contact_number";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final String KEY_STORED_IMEI = "stored_imei";
    private static final String KEY_LAST_SHUTDOWN_TIME = "last_shutdown_time";
    private static final String KEY_PROTECTIONS_APPLIED = "protections_applied";
    private static final String KEY_APP_HIDDEN = "app_hidden";
    private static final String KEY_APP_DISABLED = "app_disabled";
    private static final String KEY_CAMERA_ACTIVE = "camera_active";
    private static final String KEY_FRP_ACCOUNT = "frp_account";
    private static final String KEY_ZTE_PROVISIONED = "zte_provisioned";

    private SharedPreferences prefs;

    private PreferenceManager(Context context) {
        try {
            // Use encrypted shared preferences for security
            MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to regular SharedPreferences if encryption fails
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static synchronized PreferenceManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreferenceManager(context.getApplicationContext());
        }
        return instance;
    }

    // Generic methods
    public void saveString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void saveBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void saveLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    // FCM Token
    public void setFcmToken(String token) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply();
    }

    public String getFcmToken() {
        return prefs.getString(KEY_FCM_TOKEN, null);
    }

    // Device ID
    public void setDeviceId(String deviceId) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, null);
    }

    // Server URL
    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "https://rr-locker-api.onrender.com/api/v1");
    }

    // Enrolled status
    public void setEnrolled(boolean enrolled) {
        prefs.edit().putBoolean(KEY_ENROLLED, enrolled).apply();
    }

    public boolean isEnrolled() {
        return prefs.getBoolean(KEY_ENROLLED, false);
    }

    // Device Admin status
    public void setDeviceAdminEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEVICE_ADMIN, enabled).apply();
    }

    public boolean isDeviceAdminEnabled() {
        return prefs.getBoolean(KEY_DEVICE_ADMIN, false);
    }

    // Device Lock status
    public void setDeviceLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_DEVICE_LOCKED, locked).apply();
    }

    public boolean isDeviceLocked() {
        return prefs.getBoolean(KEY_DEVICE_LOCKED, false);
    }

    // Lock message
    public void setLockMessage(String message) {
        prefs.edit().putString(KEY_LOCK_MESSAGE, message).apply();
    }

    public String getLockMessage() {
        return prefs.getString(KEY_LOCK_MESSAGE, "Your device has been locked by the administrator.");
    }

    // Contact number
    public void setContactNumber(String number) {
        prefs.edit().putString(KEY_CONTACT_NUMBER, number).apply();
    }

    public String getContactNumber() {
        return prefs.getString(KEY_CONTACT_NUMBER, "");
    }

    // Stored IMEI (for binding)
    public void setStoredIMEI(String imei) {
        prefs.edit().putString(KEY_STORED_IMEI, imei).commit();
    }

    public String getStoredIMEI() {
        return prefs.getString(KEY_STORED_IMEI, null);
    }

    // Last shutdown time
    public void setLastShutdownTime(long time) {
        prefs.edit().putLong(KEY_LAST_SHUTDOWN_TIME, time).commit();
    }

    public long getLastShutdownTime() {
        return prefs.getLong(KEY_LAST_SHUTDOWN_TIME, 0);
    }

    // Protections applied flag
    public void setProtectionsApplied(boolean applied) {
        prefs.edit().putBoolean(KEY_PROTECTIONS_APPLIED, applied).commit();
    }

    public boolean isProtectionsApplied() {
        return prefs.getBoolean(KEY_PROTECTIONS_APPLIED, false);
    }

    // App hidden state
    public void setAppHidden(boolean hidden) {
        prefs.edit().putBoolean(KEY_APP_HIDDEN, hidden).commit();
    }

    public boolean isAppHidden() {
        return prefs.getBoolean(KEY_APP_HIDDEN, false);
    }

    // App disabled state
    public void setAppDisabled(boolean disabled) {
        prefs.edit().putBoolean(KEY_APP_DISABLED, disabled).commit();
    }

    public boolean isAppDisabled() {
        return prefs.getBoolean(KEY_APP_DISABLED, false);
    }

    // Camera active state
    public void setCameraActive(boolean active) {
        prefs.edit().putBoolean(KEY_CAMERA_ACTIVE, active).commit();
    }

    public boolean isCameraActive() {
        return prefs.getBoolean(KEY_CAMERA_ACTIVE, false);
    }

    // FRP Google account
    public void setFRPAccount(String account) {
        prefs.edit().putString(KEY_FRP_ACCOUNT, account).commit();
    }

    public String getFRPAccount() {
        return prefs.getString(KEY_FRP_ACCOUNT, null);
    }

    // ZTE provisioned flag
    public void setZTEProvisioned(boolean provisioned) {
        prefs.edit().putBoolean(KEY_ZTE_PROVISIONED, provisioned).commit();
    }

    public boolean isZTEProvisioned() {
        return prefs.getBoolean(KEY_ZTE_PROVISIONED, false);
    }

    // Clear all preferences
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
