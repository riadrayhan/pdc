package com.riad.rrlkr.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.LockManager;
import com.riad.rrlkr.service.RebootBlockerService;
import com.riad.rrlkr.service.SamsungProtectionManager;
import com.riad.rrlkr.ui.LockTaskBootActivity;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Shutdown Monitor Receiver
 * Detects shutdown/reboot/power off attempts and takes protective action.
 * Re-applies all protections aggressively on any power event.
 * Re-launches services when screen comes back on.
 */
public class ShutdownMonitorReceiver extends BroadcastReceiver {

    private static final String TAG = "ShutdownMonitor";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "Power event: " + action);

        PreferenceManager prefs = new PreferenceManager(context);

        switch (action) {
            case Intent.ACTION_SHUTDOWN:
                Log.w(TAG, "SHUTDOWN detected! Attempting to block...");
                if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                    // Store shutdown timestamp for verification on next boot
                    prefs.setLastShutdownTime(System.currentTimeMillis());
                    
                    // CRITICAL: Backup enrollment data to device-protected storage
                    // In case shutdown proceeds and data is cleared in recovery
                    try {
                        DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(context);
                        dpPrefs.backupFrom(prefs);
                        Log.i(TAG, "Enrollment data backed up before shutdown");
                    } catch (Exception e) {
                        Log.e(TAG, "Error backing up before shutdown", e);
                    }
                    
                    // Aggressively try to prevent shutdown
                    try {
                        DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                        if (protectionManager.isDeviceOwner()) {
                            // Re-apply ALL protections to try to block shutdown
                            protectionManager.disableRebootPowerOff();
                            protectionManager.disableBootloaderAndRecovery();
                            
                            // Force screen on via wake lock
                            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                            if (pm != null) {
                                PowerManager.WakeLock wl = pm.newWakeLock(
                                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    "emilocker:shutdown_block"
                                );
                                wl.acquire(10000); // 10 seconds
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling shutdown", e);
                    }
                    
                    // Ensure service restarts even if shutdown proceeds
                    DeviceMonitorService.start(context);
                }
                break;

            case Intent.ACTION_REBOOT:
                Log.w(TAG, "REBOOT detected! Logging and protecting...");
                if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                    prefs.setLastShutdownTime(System.currentTimeMillis());
                    
                    // Try to re-apply protections before reboot
                    try {
                        DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                        if (protectionManager.isDeviceOwner()) {
                            protectionManager.disableBootloaderAndRecovery();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling reboot", e);
                    }
                }
                break;

            case Intent.ACTION_SCREEN_ON:
                Log.d(TAG, "Screen ON");
                if (prefs.isEnrolled()) {
                    // Restart monitoring service
                    DeviceMonitorService.start(context);
                    
                    // Start RebootBlockerService
                    RebootBlockerService.start(context);
                    
                    // Check lock status
                    if (prefs.isDeviceLocked()) {
                        LockManager.showLockScreen(context);
                    }
                    
                    // Re-apply ALL protections (not just USB) on screen wake
                    if (!prefs.isAppDisabled()) {
                        DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                        if (protectionManager.isDeviceOwner()) {
                            protectionManager.disableUSBDebugging();
                            protectionManager.disableADB();
                            protectionManager.disableUSBDataPermanently();
                            protectionManager.disableBootloaderAndRecovery();
                            protectionManager.blockAppsControl();
                            protectionManager.enablePowerButtonInterceptService();
                            protectionManager.disableFlashAndFirmware();
                            
                            // Samsung-specific re-enforcement on screen wake
                            try {
                                SamsungProtectionManager samsungManager = new SamsungProtectionManager(context);
                                samsungManager.applyAllSamsungProtections();
                            } catch (Exception e) { /* ignore */ }
                            
                            // Re-activate lock task mode on every screen-on
                            LockTaskBootActivity.launch(context);
                        }
                    }
                }
                break;

            case Intent.ACTION_SCREEN_OFF:
                Log.w(TAG, "Screen OFF â€” FORCING BACK ON (power button disabled)");
                // Ensure service keeps running
                if (prefs.isEnrolled()) {
                    DeviceMonitorService.start(context);
                    RebootBlockerService.start(context);
                    
                    // IMMEDIATELY force screen back on
                    forceScreenOn(context);
                    
                    // Re-apply protections when screen off
                    if (!prefs.isAppDisabled()) {
                        DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                        if (protectionManager.isDeviceOwner()) {
                            protectionManager.disableUSBDataPermanently();
                            protectionManager.disableBootloaderAndRecovery();
                        }
                    }
                }
                break;

            case Intent.ACTION_USER_PRESENT:
                Log.d(TAG, "User present (unlocked)");
                if (prefs.isEnrolled() && prefs.isDeviceLocked()) {
                    // Re-show lock screen after device unlock
                    LockManager.showLockScreen(context);
                }
                break;
        }
    }

    /**
     * Force the screen on using multiple WakeLock types
     */
    @SuppressWarnings("deprecation")
    private void forceScreenOn(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;

            // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP
            try {
                PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK 
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP 
                    | PowerManager.ON_AFTER_RELEASE,
                    "emilocker:shutdown_monitor_wake"
                );
                wl.acquire(15000);
            } catch (Exception e) { /* ignore */ }

            // SCREEN_BRIGHT_WAKE_LOCK
            try {
                PowerManager.WakeLock wl2 = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK 
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "emilocker:shutdown_monitor_bright"
                );
                wl2.acquire(15000);
            } catch (Exception e) { /* ignore */ }

            Log.i(TAG, "Screen forced ON");
        } catch (Exception e) {
            Log.e(TAG, "Error forcing screen on", e);
        }
    }
}
