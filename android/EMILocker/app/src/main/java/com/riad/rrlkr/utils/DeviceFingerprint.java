package com.riad.rrlkr.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.riad.rrlkr.receiver.FactoryResetProtectionReceiver;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.UUID;

/**
 * Collects device fingerprint data for identification
 * This helps identify the device even after factory reset
 */
public class DeviceFingerprint {
    
    private static final String TAG = "DeviceFingerprint";
    
    private final Context context;
    private final PreferenceManager preferenceManager;
    
    public DeviceFingerprint(Context context) {
        this.context = context;
        this.preferenceManager = PreferenceManager.getInstance(context);
    }
    
    /**
     * Check if app is Device Owner (required for IMEI on Android 10+)
     */
    public boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean isOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        Log.i(TAG, "=== Device Owner Check ===");
        Log.i(TAG, "Package: " + context.getPackageName());
        Log.i(TAG, "Is Device Owner: " + isOwner);
        if (dpm != null) {
            Log.i(TAG, "DPM available: true");
        }
        return isOwner;
    }
    
    /**
     * Get primary IMEI (SIM slot 1)
     * Note: On Android 10+ requires Device Owner mode
     */
    @SuppressLint("HardwareIds")
    public String getIMEI() {
        Log.i(TAG, "=== Getting IMEI ===");
        Log.i(TAG, "Android SDK: " + Build.VERSION.SDK_INT);
        
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.w(TAG, "TelephonyManager is null");
                return null;
            }
            Log.i(TAG, "TelephonyManager OK");
            
            // Check permission first
            boolean hasPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "READ_PHONE_STATE permission: " + hasPermission);
            if (!hasPermission) {
                Log.w(TAG, "READ_PHONE_STATE permission not granted");
                return null;
            }
            
            // Android 10+ requires Device Owner for IMEI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean deviceOwner = isDeviceOwner();
                Log.i(TAG, "Android 10+ - Device Owner: " + deviceOwner);
                if (!deviceOwner) {
                    Log.w(TAG, "IMEI access requires Device Owner on Android 10+. Trying MEID...");
                    return tryGetMeid(telephonyManager);
                }
                Log.i(TAG, "Device Owner mode enabled, attempting IMEI access...");
            }
            
            // Get IMEI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.i(TAG, "Calling telephonyManager.getImei(0)...");
                String imei = telephonyManager.getImei(0);
                Log.i(TAG, "IMEI result: " + (imei != null ? imei : "NULL"));
                if (imei != null && !imei.isEmpty()) {
                    Log.i(TAG, "IMEI retrieved successfully: " + imei);
                    return imei;
                }
                // Try MEID as fallback for CDMA devices
                Log.i(TAG, "IMEI null, trying MEID...");
                String meid = tryGetMeid(telephonyManager);
                if (meid != null) return meid;
            } else {
                String deviceId = telephonyManager.getDeviceId();
                Log.i(TAG, "Device ID retrieved: " + (deviceId != null ? "Yes" : "No"));
                return deviceId;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException getting IMEI: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Error getting IMEI", e);
            e.printStackTrace();;
        }
        return null;
    }
    
    /**
     * Get secondary IMEI (SIM slot 2) for dual SIM devices
     */
    @SuppressLint("HardwareIds")
    public String getIMEI2() {
        try {
            // Android 10+ requires Device Owner for IMEI2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isDeviceOwner()) {
                return null;
            }
            
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        return telephonyManager.getImei(1);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting IMEI2: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Try to get MEID (Mobile Equipment Identifier) as fallback for CDMA devices
     */
    @SuppressLint("HardwareIds")
    private String tryGetMeid(TelephonyManager telephonyManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String meid = telephonyManager.getMeid(0);
                if (meid != null && !meid.isEmpty()) {
                    Log.i(TAG, "MEID retrieved as fallback");
                    return meid;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "MEID also not accessible: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Error getting MEID: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Get device serial number
     * Note: On Android 10+ requires Device Owner mode
     */
    @SuppressLint("HardwareIds")
    public String getSerialNumber() {
        try {
            // Android 10+ requires Device Owner for Serial
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!isDeviceOwner()) {
                    Log.w(TAG, "Serial access requires Device Owner on Android 10+");
                    // Try system property fallback (may work on some devices)
                    return tryGetSerialFromSystemProperty();
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    String serial = Build.getSerial();
                    if (serial != null && !serial.isEmpty() && !serial.equals("unknown")) {
                        Log.i(TAG, "Serial retrieved via Build.getSerial()");
                        return serial;
                    }
                } else {
                    Log.w(TAG, "READ_PHONE_STATE permission not granted for Serial");
                }
            } else {
                String serial = Build.SERIAL;
                if (serial != null && !serial.isEmpty() && !serial.equals("unknown")) {
                    return serial;
                }
            }
            
            // Try system property fallback
            return tryGetSerialFromSystemProperty();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException getting Serial: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error getting Serial", e);
        }
        return null;
    }
    
    /**
     * Try to get serial from system property (fallback method)
     */
    private String tryGetSerialFromSystemProperty() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @SuppressWarnings("deprecation")
                String serial = Build.SERIAL;
                if (serial != null && !serial.isEmpty() && !serial.equals("unknown")) {
                    return serial;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get serial: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Get status message about IMEI/Serial availability
     */
    public String getAccessStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "IMEI/Serial: Available (Android 9 or below)";
        }
        
        if (isDeviceOwner()) {
            return "IMEI/Serial: Available (Device Owner mode)";
        }
        
        return "IMEI/Serial: Requires Device Owner mode on Android 10+\n" +
               "Setup via QR code provisioning during factory reset";
    }
    
    /**
     * Get Android ID (changes after factory reset)
     */
    @SuppressLint("HardwareIds")
    public String getAndroidId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }
    
    /**
     * Generate a persistent device ID that survives factory reset
     * Based on hardware identifiers
     */
    public String getPersistentDeviceId() {
        StringBuilder sb = new StringBuilder();
        
        // Add IMEI (doesn't change after reset)
        String imei = getIMEI();
        if (imei != null && !imei.isEmpty()) {
            sb.append(imei);
        }
        
        // Add Serial Number (doesn't change after reset)
        String serial = getSerialNumber();
        if (serial != null && !serial.equals("unknown")) {
            sb.append(serial);
        }
        
        // Add hardware info
        sb.append(Build.BOARD);
        sb.append(Build.BRAND);
        sb.append(Build.DEVICE);
        sb.append(Build.HARDWARE);
        sb.append(Build.MANUFACTURER);
        sb.append(Build.MODEL);
        sb.append(Build.PRODUCT);
        
        // Generate hash
        return generateHash(sb.toString());
    }
    
    /**
     * Get complete device fingerprint as JSON
     */
    public JSONObject getFullFingerprint() {
        JSONObject fingerprint = new JSONObject();
        try {
            // Hardware identifiers
            fingerprint.put("imei", getIMEI());
            fingerprint.put("imei2", getIMEI2());
            fingerprint.put("serial_number", getSerialNumber());
            fingerprint.put("android_id", getAndroidId());
            fingerprint.put("persistent_id", getPersistentDeviceId());
            
            // Device info
            fingerprint.put("manufacturer", Build.MANUFACTURER);
            fingerprint.put("brand", Build.BRAND);
            fingerprint.put("model", Build.MODEL);
            fingerprint.put("device", Build.DEVICE);
            fingerprint.put("product", Build.PRODUCT);
            fingerprint.put("board", Build.BOARD);
            fingerprint.put("hardware", Build.HARDWARE);
            
            // Software info
            fingerprint.put("android_version", Build.VERSION.RELEASE);
            fingerprint.put("sdk_version", Build.VERSION.SDK_INT);
            fingerprint.put("build_id", Build.ID);
            fingerprint.put("build_fingerprint", Build.FINGERPRINT);
            
            // Display info
            fingerprint.put("display", Build.DISPLAY);
            
            // SIM info
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                fingerprint.put("sim_operator", tm.getSimOperator());
                fingerprint.put("sim_operator_name", tm.getSimOperatorName());
                fingerprint.put("network_operator", tm.getNetworkOperator());
                fingerprint.put("phone_type", tm.getPhoneType());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fingerprint;
    }
    
    /**
     * Check if this device was previously enrolled
     * Compare with stored fingerprint
     */
    public boolean wasDevicePreviouslyEnrolled() {
        String storedPersistentId = preferenceManager.getString("persistent_device_id", null);
        if (storedPersistentId == null) {
            return false;
        }
        return storedPersistentId.equals(getPersistentDeviceId());
    }
    
    /**
     * Store device fingerprint locally
     */
    public void storeFingerprint() {
        preferenceManager.saveString("persistent_device_id", getPersistentDeviceId());
        preferenceManager.saveString("device_imei", getIMEI());
        preferenceManager.saveString("device_serial", getSerialNumber());
    }
    
    /**
     * Generate SHA-256 hash
     */
    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
