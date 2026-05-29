package com.riad.rrlkr.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.SamsungProtectionManager;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Persistent transparent Activity that holds Lock Task mode active.
 * 
 * Lock Task mode can ONLY be active while an Activity is running.
 * This activity stays alive (transparent, no UI) in the background to ensure
 * lock task mode is ALWAYS active, which blocks:
 * - Power menu (reboot/shutdown/restart)
 * - Home button (when in full kiosk mode on lock screen)
 * - Recent apps button
 * 
 * It launches on boot via BootReceiver and re-launches itself if destroyed.
 *
 * SAMSUNG APPROACH (matching SOTI/Hexnode/professional MDMs):
 * Samsung uses Knox APIs (RestrictionPolicy.allowPowerOff) to block power menu.
 * Lock task mode is NOT used on Samsung for normal operation â€” only for kiosk
 * (lock screen). Using lock task for normal mode on Samsung causes
 * "Something went wrong, contact your IT admin" errors because Samsung's
 * SystemUI implementation conflicts with lock task mode.
 *
 * CRITICAL: Never call Thread.sleep() on the UI thread. Use Handler.postDelayed().
 */
public class LockTaskBootActivity extends Activity {

    private static final String TAG = "LockTaskBoot";
    private static boolean sIsRunning = false;
    private Handler handler;
    private PreferenceManager prefs;
    private static final boolean IS_SAMSUNG = Build.MANUFACTURER != null &&
            Build.MANUFACTURER.toLowerCase().contains("samsung");

    /**
     * Launch this activity to activate lock task mode from a non-Activity context.
     * Only launches if not already running.
     */
    public static void launch(Context context) {
        if (sIsRunning) {
            Log.d(TAG, "LockTaskBootActivity already running, skipping launch");
            return;
        }
        try {
            Intent intent = new Intent(context, LockTaskBootActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            // NOTE: DO NOT add FLAG_ACTIVITY_NO_HISTORY â€” that flag causes the system
            // to destroy the activity immediately when moveTaskToBack() is called,
            // which kills lock task mode. The activity must persist in background.
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching LockTaskBootActivity", e);
        }
    }

    /**
     * Force re-launch, even if sIsRunning is true.
     * Used by DeviceMonitorService when lock task mode has been lost.
     */
    public static void forceRelaunch(Context context) {
        Log.w(TAG, "Force re-launching LockTaskBootActivity");
        sIsRunning = false; // Reset flag to allow re-launch
        try {
            Intent intent = new Intent(context, LockTaskBootActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK); // Clear any stale task
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error force re-launching LockTaskBootActivity", e);
        }
    }

    /**
     * Check if lock task mode is currently active on the device
     */
    public static boolean isLockTaskActive(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        }
        return am.isInLockTaskMode();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "LockTaskBootActivity starting (Samsung=" + IS_SAMSUNG + ")");

        // Make fully transparent, no visibility
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        prefs = new PreferenceManager(this);
        handler = new Handler(Looper.getMainLooper());

        // Skip if admin disabled the app
        if (prefs.isAppDisabled()) {
            Log.d(TAG, "App disabled by admin, skipping lock task activation");
            sIsRunning = false;
            finish();
            return;
        }

        // Skip during active ZTE provisioning â€” don't start lock task mode
        // until enrollment is complete, otherwise phone appears locked
        if (prefs.getBoolean("zte_provisioning_active", false)) {
            Log.d(TAG, "ZTE provisioning active, skipping lock task activation");
            sIsRunning = false;
            finish();
            return;
        }

        // Skip if not enrolled yet
        if (!prefs.isEnrolled()) {
            Log.d(TAG, "Device not enrolled yet, skipping lock task activation");
            sIsRunning = false;
            finish();
            return;
        }

        sIsRunning = true;

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                // Configure lock task packages & features (prepares for future lock task use)
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                protectionManager.disableRebootPowerOff();

                if (IS_SAMSUNG) {
                    // ===================== SAMSUNG PATH =====================
                    // Samsung: Use Knox APIs to block power menu (like SOTI does).
                    // Do NOT use lock task mode for normal operation â€”
                    // it causes "Something went wrong" on Samsung.
                    SamsungProtectionManager samsungMgr = new SamsungProtectionManager(this);
                    boolean knoxBlocked = samsungMgr.blockPowerOffViaKnox();
                    Log.i(TAG, "Samsung: Knox power block = " + knoxBlocked);

                    if (prefs.isDeviceLocked()) {
                        // Device IS locked â€” need kiosk mode, use lock task
                        Log.i(TAG, "Samsung: Device locked â€” activating lock task for kiosk");
                        // Schedule lock task on next UI cycle (never block UI thread)
                        handler.post(() -> {
                            try {
                                startLockTask();
                                Log.i(TAG, "Samsung: Lock task STARTED for kiosk mode");
                            } catch (Exception e) {
                                Log.e(TAG, "Samsung: startLockTask for kiosk failed: " + e.getMessage());
                            }
                            // Redirect to lock screen
                            Intent lockIntent = new Intent(LockTaskBootActivity.this, LockScreenActivity.class);
                            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            lockIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(lockIntent);
                        });
                    } else {
                        // Device NOT locked â€” Samsung Knox handles power blocking,
                        // no lock task needed. Finish this activity.
                        Log.i(TAG, "Samsung: Device unlocked â€” Knox-only mode, no lock task");
                        sIsRunning = false;
                        finish();
                        return;
                    }
                } else {
                    // ===================== NON-SAMSUNG PATH =====================
                    // Non-Samsung: Use lock task mode to block power menu
                    startLockTask();
                    Log.i(TAG, "Lock Task Mode ACTIVATED â€” power menu BLOCKED");

                    // Check if device should show lock screen
                    if (prefs.isDeviceLocked()) {
                        Log.i(TAG, "Device is locked â€” redirecting to LockScreenActivity");
                        Intent lockIntent = new Intent(this, LockScreenActivity.class);
                        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        lockIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(lockIntent);
                    }

                    // Move to back so it's invisible but alive (holds lock task)
                    handler.postDelayed(() -> {
                        try {
                            moveTaskToBack(true);
                        } catch (Exception e) {
                            Log.w(TAG, "Could not move to back: " + e.getMessage());
                        }
                    }, 300);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in lock task setup", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Samsung in normal (unlocked) mode: this activity should not be alive
        if (IS_SAMSUNG && prefs != null && !prefs.isDeviceLocked()) {
            Log.d(TAG, "Samsung unlocked mode â€” finishing LockTaskBootActivity");
            sIsRunning = false;
            finish();
            return;
        }

        // Re-enter lock task mode if it has been lost (non-Samsung, or Samsung kiosk)
        if (!isLockTaskActive(this)) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                    protectionManager.disableRebootPowerOff();
                    startLockTask();
                    Log.i(TAG, "Lock Task Mode RE-ACTIVATED in onResume");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error re-activating lock task in onResume", e);
            }
        }

        // Move to back after a short delay to ensure lock task takes effect
        handler.postDelayed(() -> {
            try {
                moveTaskToBack(true);
            } catch (Exception e) {
                Log.w(TAG, "Could not move to back: " + e.getMessage());
            }
        }, 300);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Block power button, home, recents at this level too
        if (keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sIsRunning = false;
        Log.w(TAG, "LockTaskBootActivity destroyed!");

        // Re-launch if destroyed (unless app is disabled)
        if (prefs != null && !prefs.isAppDisabled() && prefs.isEnrolled()) {
            Log.i(TAG, "Re-launching LockTaskBootActivity to maintain lock task mode");
            handler.postDelayed(() -> launch(getApplicationContext()), 500);
        }
    }

    @Override
    public void onBackPressed() {
        // Block back button
    }
}
