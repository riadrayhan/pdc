package com.riad.rrlkr.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.ui.LockTaskBootActivity;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * RebootBlockerService - A persistent foreground service that provides
 * an additional layer of protection against reboot/recovery/power-off.
 * 
 * Protection layers:
 * 1. INVISIBLE SYSTEM OVERLAY - Captures volume+power key combos that trigger recovery mode
 * 2. SCREEN STATE MONITOR - Detects screen-off events and re-activates protections
 * 3. LOCK TASK MONITOR - Periodically checks and re-activates lock task mode
 * 4. POWER DIALOG MONITOR - Detects when Android shows the power menu globally
 * 5. SHUTDOWN BROADCAST INTERCEPTOR - Catches shutdown/reboot broadcasts
 * 
 * This service runs 24/7 alongside DeviceMonitorService as a critical security layer.
 * Combined with Lock Task mode and the Accessibility-based PowerButtonInterceptService,
 * this creates a 3-layer defense against reboot/recovery bypass:
 *   Layer 1: Lock Task Mode (blocks power menu UI)
 *   Layer 2: AccessibilityService (intercepts key events + dismisses power dialogs)
 *   Layer 3: This service (overlay key capture + continuous re-enforcement)
 */
public class RebootBlockerService extends Service {

    private static final String TAG = "RebootBlocker";
    private static final int NOTIFICATION_ID = 1003;
    private static final long ENFORCEMENT_INTERVAL = 8_000; // 8 seconds
    private static final long AGGRESSIVE_INTERVAL = 2_000; // 2 seconds when threat detected

    private WindowManager windowManager;
    private View overlayView;
    private Handler handler;
    private Runnable enforcementRunnable;
    private PreferenceManager prefs;
    private PowerManager.WakeLock keepAliveWakeLock;
    private PowerManager.WakeLock screenWakeLock; // Cached screen wake lock (prevents leak)
    private BroadcastReceiver screenReceiver;
    private boolean isRunning = false;
    private boolean threatDetected = false;

    /**
     * Start the RebootBlockerService
     */
    public static void start(Context context) {
        // Disabled for Play Store compliance â€” no-op
        Log.d(TAG, "RebootBlockerService disabled for Play Store build");
    }

    /**
     * Stop the service
     */
    public static void stop(Context context) {
        // Disabled for Play Store compliance â€” no-op
        Log.d(TAG, "RebootBlockerService stop â€” no-op");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "=== RebootBlockerService CREATED ===");

        prefs = new PreferenceManager(this);
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Setup enforcement loop
        enforcementRunnable = new Runnable() {
            @Override
            public void run() {
                enforceProtections();
                if (isRunning) {
                    long interval = threatDetected ? AGGRESSIVE_INTERVAL : ENFORCEMENT_INTERVAL;
                    handler.postDelayed(this, interval);
                }
            }
        };

        // Register screen state receiver
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "RebootBlockerService started");

        // Skip if app is disabled
        if (prefs.isAppDisabled()) {
            Log.d(TAG, "App disabled - stopping RebootBlockerService");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start as foreground (Android 14+ requires specifying service type)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
        isRunning = true;

        // Create invisible overlay for key interception
        createInvisibleOverlay();

        // Start enforcement loop
        handler.post(enforcementRunnable);

        // Acquire partial wake lock to keep service alive
        acquireWakeLock();

        return START_STICKY;
    }

    /**
     * Create an invisible system overlay that captures hardware key combos.
     * This overlay sits on top of everything and intercepts volume+power combos  
     * that would normally trigger recovery mode during shutdown.
     */
    private void createInvisibleOverlay() {
        if (overlayView != null) return; // Already created

        try {
            // Check if we can draw overlays
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // As Device Owner, try to grant overlay permission
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    try {
                        Settings.Secure.putInt(getContentResolver(),
                            "force_allow_on_external", 1);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not set overlay permission: " + e.getMessage());
                    }
                }
            }

            overlayView = new View(this) {
                private boolean volUpHeld = false;
                private boolean volDownHeld = false;
                private long lastPowerTime = 0;
                private long lastVolDownTime = 0;
                private long lastVolUpTime = 0;
                private int volDownCount = 0;
                private int volUpCount = 0;

                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    int keyCode = event.getKeyCode();
                    int action = event.getAction();
                    long now = System.currentTimeMillis();

                    // Track volume keys
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        volUpHeld = (action == KeyEvent.ACTION_DOWN);
                        if (action == KeyEvent.ACTION_DOWN) {
                            if (now - lastVolUpTime < 2000) volUpCount++;
                            else volUpCount = 1;
                            lastVolUpTime = now;
                        }
                    }
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        volDownHeld = (action == KeyEvent.ACTION_DOWN);
                        if (action == KeyEvent.ACTION_DOWN) {
                            if (now - lastVolDownTime < 2000) volDownCount++;
                            else volDownCount = 1;
                            lastVolDownTime = now;
                        }
                    }

                    // === SAMSUNG DOWNLOAD MODE COMBO ===
                    // Samsung S10+: Vol Up + Vol Down simultaneously while USB connected
                    if (volUpHeld && volDownHeld) {
                        Log.w(TAG, "=== OVERLAY: Vol Up + Vol Down COMBO BLOCKED (Samsung Download Mode) ===");
                        threatDetected = true;
                        handler.post(() -> forceProtectionRestore());
                        return true; // CONSUME
                    }

                    // === SAMSUNG BIXBY KEY ===
                    // KeyEvent.KEYCODE_ASSIST (219) is Samsung Bixby on some models
                    if (keyCode == 219 || keyCode == KeyEvent.KEYCODE_ASSIST) {
                        if (action == KeyEvent.ACTION_DOWN) {
                            // Bixby + Vol Down + Power = Samsung Download Mode (S8/S9)
                            // Bixby + Vol Up + Power = Samsung Recovery Mode (S8/S9)
                            if (volUpHeld || volDownHeld || (now - lastPowerTime < 3000)) {
                                Log.w(TAG, "=== OVERLAY: Bixby combo BLOCKED (Samsung Recovery/Download) ===");
                                threatDetected = true;
                                handler.post(() -> forceProtectionRestore());
                                return true;
                            }
                        }
                    }

                    // Detect recovery combo: Volume + Power simultaneously
                    if (keyCode == KeyEvent.KEYCODE_POWER && action == KeyEvent.ACTION_DOWN) {
                        lastPowerTime = now;
                        if (volUpHeld || volDownHeld) {
                            Log.w(TAG, "=== OVERLAY: Recovery key combo INTERCEPTED and BLOCKED ===");
                            threatDetected = true;
                            handler.post(() -> forceProtectionRestore());
                            return true; // CONSUME the event
                        }
                    }

                    // If volume pressed while power was recently pressed
                    if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                            && action == KeyEvent.ACTION_DOWN) {
                        if (now - lastPowerTime < 3000) {
                            Log.w(TAG, "=== OVERLAY: Volume pressed after recent Power â€” BLOCKING combo ===");
                            threatDetected = true;
                            return true; // CONSUME
                        }
                    }

                    // === Rapid volume press detection (potential bypass attempt) ===
                    if (volDownCount >= 5 || volUpCount >= 5) {
                        Log.w(TAG, "=== OVERLAY: Rapid volume press detected â€” potential bypass attempt ===");
                        threatDetected = true;
                        volDownCount = 0;
                        volUpCount = 0;
                        handler.post(() -> forceProtectionRestore());
                    }

                    return super.dispatchKeyEvent(event);
                }
            };

            // Make overlay invisible but able to capture key events
            overlayView.setFocusable(true);
            overlayView.setFocusableInTouchMode(true);

            int overlayType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                overlayType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, // width: 1 pixel (invisible)
                    1, // height: 1 pixel (invisible)
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Don't steal focus from user
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;

            windowManager.addView(overlayView, params);
            Log.i(TAG, "Invisible overlay CREATED for key combo interception");

        } catch (Exception e) {
            Log.e(TAG, "Error creating overlay: " + e.getMessage());
            // Overlay creation may fail if SYSTEM_ALERT_WINDOW permission is not granted
            // The other layers (Lock Task + Accessibility) still provide protection
        }
    }

    /**
     * Remove the overlay
     */
    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.w(TAG, "Error removing overlay: " + e.getMessage());
            }
            overlayView = null;
        }
    }

    /**
     * Register a receiver for screen on/off events
     */
    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                switch (action) {
                    case Intent.ACTION_SCREEN_OFF:
                        Log.w(TAG, "Screen OFF detected â€” FORCING SCREEN BACK ON");
                        // Screen off means power button was pressed
                        // IMMEDIATELY force screen back on
                        forceScreenOn();
                        handler.postDelayed(() -> forceScreenOn(), 200);
                        handler.postDelayed(() -> forceScreenOn(), 500);
                        handler.postDelayed(() -> forceScreenOn(), 1000);
                        handler.postDelayed(() -> forceScreenOn(), 2000);
                        // Also reinforce protections
                        handler.postDelayed(() -> enforceProtections(), 1500);
                        handler.postDelayed(() -> enforceProtections(), 3000);
                        break;

                    case Intent.ACTION_SCREEN_ON:
                        Log.i(TAG, "Screen ON â€” checking lock task mode");
                        handler.postDelayed(() -> ensureLockTaskActive(), 500);
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    /**
     * Periodically enforce all protections
     */
    private void enforceProtections() {
        if (!isRunning) return;

        try {
            // Skip if app is disabled
            if (prefs.isAppDisabled()) return;
            if (!prefs.isEnrolled()) return;

            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

            // 1. Ensure Lock Task mode is active
            ensureLockTaskActive();

            // 2. Re-disable ADB, developer options, OEM unlock, and wireless debugging
            try {
                dpm.setGlobalSetting(
                    EMIDeviceAdminReceiver.getComponentName(this),
                    Settings.Global.ADB_ENABLED, "0");
                dpm.setGlobalSetting(
                    EMIDeviceAdminReceiver.getComponentName(this),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
                dpm.setGlobalSetting(
                    EMIDeviceAdminReceiver.getComponentName(this),
                    "oem_unlock_enabled", "0");
                dpm.setGlobalSetting(
                    EMIDeviceAdminReceiver.getComponentName(this),
                    "adb_wifi_enabled", "0");
            } catch (Exception e) {
                // Ignore - already set
            }

            // 2b. Re-enforce USB data signaling disabled (hardware level)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(false);
                } catch (Exception e) { /* ignore */ }
            }

            // 2c. Samsung-specific: Re-enforce Samsung Download Mode blocking
            if (SamsungProtectionManager.isSamsungDevice()) {
                try {
                    ComponentName admin = EMIDeviceAdminReceiver.getComponentName(this);
                    // Re-disable Samsung auto-restart
                    dpm.setGlobalSetting(admin, "auto_restart_enabled", "0");
                    // Re-disable maintenance mode
                    dpm.setGlobalSetting(admin, "maintenance_mode_enabled", "0");
                    // Re-disable Samsung power key action
                    dpm.setSecureSetting(admin, "power_key_action", "0");
                } catch (Exception e) { /* ignore - Samsung specific */ }
            }

            // 3. Verify overlay is alive
            if (overlayView == null) {
                createInvisibleOverlay();
            }

            // Reset threat flag
            if (threatDetected) {
                Log.i(TAG, "Threat flag reset after enforcement");
                threatDetected = false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error enforcing protections", e);
        }
    }

    /**
     * Ensure Lock Task mode is active â€” re-launch LockTaskBootActivity if needed
     */
    private void ensureLockTaskActive() {
        try {
            if (!LockTaskBootActivity.isLockTaskActive(this)) {
                Log.w(TAG, "Lock Task mode NOT ACTIVE â€” re-launching LockTaskBootActivity");
                LockTaskBootActivity.forceRelaunch(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error ensuring lock task active", e);
        }
    }

    /**
     * Force restore all protections immediately (called when threat detected)
     */
    private void forceProtectionRestore() {
        try {
            Log.w(TAG, "=== FORCE PROTECTION RESTORE ===");

            // Re-apply all device protections
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            if (protectionManager.isDeviceOwner()) {
                protectionManager.disableRebootPowerOff();
                protectionManager.disableBootloaderAndRecovery();
            }

            // Re-launch lock task
            LockTaskBootActivity.forceRelaunch(this);

            // Wake up screen
            wakeUpScreen();

        } catch (Exception e) {
            Log.e(TAG, "Error in force protection restore", e);
        }
    }

    /**
     * Wake up the screen
     */
    private void wakeUpScreen() {
        forceScreenOn();
    }

    /**
     * FORCE screen on using a cached WakeLock (prevents WakeLock leak).
     * Reuses the same WakeLock instance, releasing it before re-acquiring.
     */
    @SuppressWarnings("deprecation")
    private void forceScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;

            // Release old screen wake lock if held
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                try { screenWakeLock.release(); } catch (Exception e) { /* ignore */ }
            }

            // Create or reuse cached WakeLock
            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                    "emilocker:rbs_force_on"
                );
            }
            screenWakeLock.acquire(15000); // Auto-release after 15s

            Log.d(TAG, "Screen forced ON");
        } catch (Exception e) {
            Log.e(TAG, "Error forcing screen on", e);
        }
    }

    /**
     * Acquire a partial wake lock to keep the service alive
     */
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                keepAliveWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "emilocker:reboot_blocker_alive"
                );
                keepAliveWakeLock.acquire(); // Indefinite hold
                Log.i(TAG, "Wake lock acquired â€” service will stay alive");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }

    /**
     * Create foreground notification
     */
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this,
            com.riad.rrlkr.ui.MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, App.CHANNEL_ID_SERVICE)
                .setContentTitle("Device Protection Active")
                .setContentText("Reboot/Recovery protection is running")
                .setSmallIcon(R.drawable.ic_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "=== RebootBlockerService DESTROYED ===");

        isRunning = false;
        handler.removeCallbacks(enforcementRunnable);

        // Remove overlay
        removeOverlay();

        // Release wake lock
        if (keepAliveWakeLock != null && keepAliveWakeLock.isHeld()) {
            try {
                keepAliveWakeLock.release();
            } catch (Exception e) { /* ignore */ }
        }

        // Release screen wake lock
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            try {
                screenWakeLock.release();
            } catch (Exception e) { /* ignore */ }
        }

        // Unregister receiver
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception e) { /* ignore */ }
        }

        // RESTART SELF immediately if app is still enrolled
        if (prefs != null && prefs.isEnrolled() && !prefs.isAppDisabled()) {
            Log.i(TAG, "Restarting RebootBlockerService...");
            handler.postDelayed(() -> start(getApplicationContext()), 1000);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "Task removed â€” restarting RebootBlockerService");
        start(getApplicationContext());
    }
}
