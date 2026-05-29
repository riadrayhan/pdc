package com.riad.rrlkr.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.riad.rrlkr.ui.LockScreenActivity;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Manages the lock screen display
 */
public class LockManager {
    
    private static final String TAG = "LockManager";
    
    /**
     * Show the lock screen
     */
    public static void showLockScreen(Context context) {
        showLockScreen(context, null, null);
    }
    
    /**
     * Show the lock screen with custom message.
     * Lock is blocked during ZTE enrollment to prevent auto-lock on fresh provisioning.
     */
    public static void showLockScreen(Context context, String message, String contactNumber) {
        if (context == null) {
            Log.e(TAG, "Cannot show lock screen: context is null");
            return;
        }
        
        PreferenceManager prefs = new PreferenceManager(context);
        DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(context);
        
        // CRITICAL: Block lock during Zero Touch Enrollment
        // After QR provisioning, the device must stay unlocked until admin explicitly locks it
        boolean zteProvisioningActive = false;
        boolean adminLockCommand = false;
        try {
            zteProvisioningActive = prefs.getBoolean("zte_provisioning_active", false);
            adminLockCommand = prefs.getBoolean("admin_lock_command", false);
        } catch (Exception e) {
            // During Direct Boot, regular prefs may fail â€” check device-protected
            adminLockCommand = dpPrefs.getBoolean("admin_lock_command", false);
        }
        boolean zteProvisioned = false;
        try { zteProvisioned = prefs.getBoolean("zte_provisioned", false); } catch (Exception e) { /* ignore */ }
        
        if ((zteProvisioned || zteProvisioningActive) && !adminLockCommand) {
            Log.w(TAG, "BLOCKED: Lock attempt during/after ZTE â€” only admin panel can lock device");
            return;
        }
        
        Log.i(TAG, "Showing lock screen");
        
        // Save lock state to BOTH regular and device-protected storage
        try { prefs.setDeviceLocked(true); } catch (Exception e) { Log.w(TAG, "Regular prefs write failed"); }
        
        // Save to device-protected storage (survives data clear + Direct Boot)
        try {
            dpPrefs.setDeviceLocked(true);
            dpPrefs.saveBoolean("admin_lock_command", true);
        } catch (Exception e) {
            Log.e(TAG, "Error saving lock state to device-protected storage", e);
        }
        
        if (message != null) {
            try { prefs.setLockMessage(message); } catch (Exception e) { /* ignore */ }
        }
        if (contactNumber != null) {
            try { prefs.setContactNumber(contactNumber); } catch (Exception e) { /* ignore */ }
        }
        
        // Launch lock screen activity with try-catch (can fail during Direct Boot or restricted context)
        try {
            Intent intent = new Intent(context, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            
            if (message != null) {
                intent.putExtra(LockScreenActivity.EXTRA_MESSAGE, message);
            }
            if (contactNumber != null) {
                intent.putExtra(LockScreenActivity.EXTRA_CONTACT, contactNumber);
            }
            
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock screen activity: " + e.getMessage());
            // Lock state is already persisted â€” BootReceiver or DeviceMonitorService will retry
        }
    }
    
    /**
     * Hide the lock screen (unlock device)
     */
    public static void hideLockScreen(Context context) {
        if (context == null) {
            Log.e(TAG, "Cannot hide lock screen: context is null");
            return;
        }
        
        Log.i(TAG, "Hiding lock screen (unlocking)");
        
        // Save unlock state to BOTH storages
        try {
            PreferenceManager prefs = new PreferenceManager(context);
            prefs.setDeviceLocked(false);
            prefs.saveBoolean("admin_lock_command", false);
        } catch (Exception e) {
            Log.w(TAG, "Regular prefs unlock write failed: " + e.getMessage());
        }
        
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(context);
            dpPrefs.setDeviceLocked(false);
            dpPrefs.saveBoolean("admin_lock_command", false);
        } catch (Exception e) {
            Log.e(TAG, "Error saving unlock state to device-protected storage", e);
        }
        
        // Send broadcast to close lock screen activity
        try {
            Intent intent = new Intent(LockScreenActivity.ACTION_UNLOCK);
            intent.setPackage(context.getPackageName()); // Restrict to our app only (security)
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error sending unlock broadcast: " + e.getMessage());
        }
    }
    
    /**
     * Check if device is currently locked
     */
    public static boolean isLocked(Context context) {
        PreferenceManager prefs = new PreferenceManager(context);
        return prefs.isDeviceLocked();
    }
}
