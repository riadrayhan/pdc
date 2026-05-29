package com.riad.rrlkr.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Device-Protected Storage for critical enrollment data.
 * 
 * This storage:
 * - Is accessible during Direct Boot (before user unlock)
 * - Survives "wipe cache partition" in recovery
 * - Does NOT survive "wipe data/factory reset" in recovery
 * - Provides a SECONDARY backup of enrollment data
 * 
 * If user clears app data from Settings (which we block via DISALLOW_APPS_CONTROL),
 * the device-protected storage will still have the enrollment data.
 * 
 * If the app is still Device Owner after a data clear, we can re-enroll
 * automatically using this backup data.
 */
public class DeviceProtectedPrefs {

    private static final String TAG = "DeviceProtectedPrefs";
    private static final String PREFS_NAME = "emi_locker_device_protected";

    // Keys - mirror the critical keys from PreferenceManager
    private static final String KEY_DEVICE_ID = "dp_device_id";
    private static final String KEY_ENROLLED = "dp_enrolled";
    private static final String KEY_DEVICE_LOCKED = "dp_device_locked";
    private static final String KEY_STORED_IMEI = "dp_stored_imei";
    private static final String KEY_SERVER_URL = "dp_server_url";
    private static final String KEY_FCM_TOKEN = "dp_fcm_token";
    private static final String KEY_CONTACT_NUMBER = "dp_contact_number";
    private static final String KEY_LOCK_MESSAGE = "dp_lock_message";
    private static final String KEY_APP_DISABLED = "dp_app_disabled";
    private static final String KEY_ENROLLMENT_TIMESTAMP = "dp_enrollment_ts";
    private static final String KEY_FRP_ACCOUNT = "dp_frp_account";

    private final SharedPreferences prefs;

    public DeviceProtectedPrefs(Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use device-protected storage (accessible before user unlock)
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            // Fallback to credential-protected storage on older devices
            storageContext = context;
        }
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Backup all critical enrollment data from the main PreferenceManager
     */
    public void backupFrom(PreferenceManager mainPrefs) {
        try {
            SharedPreferences.Editor editor = prefs.edit();
            
            String deviceId = mainPrefs.getDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                editor.putString(KEY_DEVICE_ID, deviceId);
            }
            
            editor.putBoolean(KEY_ENROLLED, mainPrefs.isEnrolled());
            editor.putBoolean(KEY_DEVICE_LOCKED, mainPrefs.isDeviceLocked());
            editor.putBoolean(KEY_APP_DISABLED, mainPrefs.isAppDisabled());
            
            String imei = mainPrefs.getStoredIMEI();
            if (imei != null) editor.putString(KEY_STORED_IMEI, imei);
            
            String serverUrl = mainPrefs.getServerUrl();
            if (serverUrl != null) editor.putString(KEY_SERVER_URL, serverUrl);
            
            String fcmToken = mainPrefs.getFcmToken();
            if (fcmToken != null) editor.putString(KEY_FCM_TOKEN, fcmToken);
            
            String contact = mainPrefs.getContactNumber();
            if (contact != null) editor.putString(KEY_CONTACT_NUMBER, contact);
            
            String lockMsg = mainPrefs.getLockMessage();
            if (lockMsg != null) editor.putString(KEY_LOCK_MESSAGE, lockMsg);
            
            String frpAccount = mainPrefs.getFRPAccount();
            if (frpAccount != null) editor.putString(KEY_FRP_ACCOUNT, frpAccount);
            
            editor.putLong(KEY_ENROLLMENT_TIMESTAMP, System.currentTimeMillis());
            
            editor.commit();
            Log.i(TAG, "Critical data backed up to device-protected storage");
        } catch (Exception e) {
            Log.e(TAG, "Error backing up to device-protected storage", e);
        }
    }

    /**
     * Restore critical enrollment data TO the main PreferenceManager.
     * Called when main prefs are empty (data cleared) but device-protected storage has data.
     * @return true if any data was restored
     */
    public boolean restoreTo(PreferenceManager mainPrefs) {
        try {
            if (!isEnrolled()) {
                Log.d(TAG, "No enrollment data in device-protected storage");
                return false;
            }

            String deviceId = getDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                mainPrefs.setDeviceId(deviceId);
            }
            
            mainPrefs.setEnrolled(true);
            mainPrefs.setDeviceLocked(isDeviceLocked());
            
            String imei = getStoredIMEI();
            if (imei != null) mainPrefs.setStoredIMEI(imei);
            
            String serverUrl = getServerUrl();
            if (serverUrl != null) mainPrefs.setServerUrl(serverUrl);
            
            String fcmToken = getFcmToken();
            if (fcmToken != null) mainPrefs.setFcmToken(fcmToken);
            
            String contact = getContactNumber();
            if (contact != null) mainPrefs.setContactNumber(contact);
            
            String lockMsg = getLockMessage();
            if (lockMsg != null) mainPrefs.setLockMessage(lockMsg);
            
            String frpAccount = getFRPAccount();
            if (frpAccount != null) mainPrefs.setFRPAccount(frpAccount);
            
            Log.i(TAG, "Critical data RESTORED from device-protected storage!");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error restoring from device-protected storage", e);
            return false;
        }
    }

    // Getters
    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, null);
    }

    public boolean isEnrolled() {
        return prefs.getBoolean(KEY_ENROLLED, false);
    }

    public boolean isDeviceLocked() {
        return prefs.getBoolean(KEY_DEVICE_LOCKED, false);
    }

    public String getStoredIMEI() {
        return prefs.getString(KEY_STORED_IMEI, null);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, null);
    }

    public String getFcmToken() {
        return prefs.getString(KEY_FCM_TOKEN, null);
    }

    public String getContactNumber() {
        return prefs.getString(KEY_CONTACT_NUMBER, null);
    }

    public String getLockMessage() {
        return prefs.getString(KEY_LOCK_MESSAGE, null);
    }

    public boolean isAppDisabled() {
        return prefs.getBoolean(KEY_APP_DISABLED, false);
    }

    public String getFRPAccount() {
        return prefs.getString(KEY_FRP_ACCOUNT, null);
    }

    // Setters for important state changes
    public void setDeviceLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_DEVICE_LOCKED, locked).commit();
    }

    public void setAppDisabled(boolean disabled) {
        prefs.edit().putBoolean(KEY_APP_DISABLED, disabled).commit();
    }

    public void setEnrolled(boolean enrolled) {
        prefs.edit().putBoolean(KEY_ENROLLED, enrolled).commit();
    }

    public void saveString(String key, String value) {
        prefs.edit().putString(key, value).commit();
    }

    public void saveBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).commit();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void saveLong(String key, long value) {
        prefs.edit().putLong(key, value).commit();
    }

    public void clearAll() {
        prefs.edit().clear().commit();
    }

    /**
     * Check if we have valid backup data
     */
    public boolean hasValidBackup() {
        return isEnrolled() && getDeviceId() != null && !getDeviceId().isEmpty();
    }
}
