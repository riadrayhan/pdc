package com.riad.rrlkr.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.LockManager;
import com.riad.rrlkr.service.RebootBlockerService;
import com.riad.rrlkr.service.SamsungProtectionManager;
import com.riad.rrlkr.service.ServiceKeepAliveWorker;
import com.riad.rrlkr.ui.LockTaskBootActivity;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Boot Receiver - Starts services after device boot
 * CRITICAL: onReceive() has ~10 second ANR limit.
 * Lock screen is shown FIRST, then heavy work is done in background.
 */
public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot broadcast received: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            // === STEP 1: CHECK LOCK STATE IMMEDIATELY (must be fast) ===
            PreferenceManager prefs = new PreferenceManager(context);
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(context);
            
            boolean isLocked = false;
            boolean adminLockCmd = false;
            
            // Check DeviceProtectedPrefs FIRST (always available, even during Direct Boot)
            try {
                isLocked = dpPrefs.isDeviceLocked();
                adminLockCmd = dpPrefs.getBoolean("admin_lock_command", false);
            } catch (Exception e) {
                Log.w(TAG, "Error reading device-protected prefs: " + e.getMessage());
            }
            
            // Also check regular prefs (may fail during Direct Boot â€” that's OK)
            try {
                isLocked = isLocked || prefs.isDeviceLocked();
                adminLockCmd = adminLockCmd || prefs.getBoolean("admin_lock_command", false);
            } catch (Exception e) {
                Log.w(TAG, "Regular prefs unavailable during Direct Boot (expected)");
            }
            
            // Show lock screen FIRST before anything else â€” this must happen fast
            if (isLocked || adminLockCmd) {
                Log.i(TAG, "Device was locked before reboot â€” showing lock screen IMMEDIATELY");
                // Force the admin_lock_command flag in BOTH storages
                try { prefs.saveBoolean("admin_lock_command", true); } catch (Exception e) { /* Direct Boot */ }
                try { dpPrefs.saveBoolean("admin_lock_command", true); } catch (Exception e) { /* ignore */ }
                try { dpPrefs.setDeviceLocked(true); } catch (Exception e) { /* ignore */ }
                
                // Launch lock screen with retry
                try {
                    LockManager.showLockScreen(context);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show lock screen, retrying in 2s: " + e.getMessage());
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try { LockManager.showLockScreen(context); } catch (Exception e2) {
                            Log.e(TAG, "Lock screen retry also failed: " + e2.getMessage());
                        }
                    }, 2000);
                }
            }
            
            // === STEP 2: START MONITORING SERVICE (lightweight) ===
            DeviceMonitorService.start(context);
            ServiceKeepAliveWorker.schedule(context);
            
            // === STEP 3: HEAVY WORK IN BACKGROUND (goAsync + thread) ===
            boolean isEnrolled = prefs.isEnrolled() || dpPrefs.isEnrolled();
            boolean isDisabled = prefs.isAppDisabled() || dpPrefs.isAppDisabled();
            
            if (isEnrolled && !isDisabled) {
                final PendingResult pendingResult = goAsync();
                new Thread(() -> {
                    try {
                        Log.i(TAG, "Applying protections in background thread");
                        DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                        protectionManager.configureAppVerificationPolicy();
                        protectionManager.grantAllRuntimePermissions();
                        protectionManager.applyAllProtections();
                        // USB disable feature removed
                        protectionManager.disableBootloaderAndRecovery();

                        // Resume live streaming if it was active before the reboot
                        com.riad.rrlkr.streaming.AutoStreamManager.apply(context);
                        protectionManager.verifySystemIntegrity();
                        
                        try {
                            SamsungProtectionManager samsungManager = new SamsungProtectionManager(context);
                            samsungManager.applyAllSamsungProtections();
                        } catch (Exception e) {
                            Log.w(TAG, "Samsung protections: " + e.getMessage());
                        }
                        
                        RebootBlockerService.start(context);
                        
                        boolean zteProvisioning = prefs.getBoolean("zte_provisioning_active", false);
                        if (!zteProvisioning) {
                            LockTaskBootActivity.launch(context);
                        }
                        
                        Log.i(TAG, "All boot protections applied successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error applying boot protections: " + e.getMessage());
                    } finally {
                        pendingResult.finish();
                    }
                }, "BootProtectionThread").start();
            }
        }
    }
}
