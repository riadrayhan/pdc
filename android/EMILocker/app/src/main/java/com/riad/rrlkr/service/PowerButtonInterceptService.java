package com.riad.rrlkr.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.riad.rrlkr.ui.LockTaskBootActivity;
import com.riad.rrlkr.util.PreferenceManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ULTIMATE Power Button Blocker via Accessibility Service.
 * 
 * KEY INSIGHT: Android does NOT reliably deliver KEYCODE_POWER to onKeyEvent().
 * The power button is handled at the system/kernel level BEFORE accessibility gets it.
 * 
 * REAL SOLUTION â€” Instead of intercepting the key event (unreliable), we:
 * 1. DETECT screen turning off (ACTION_SCREEN_OFF) = power button was pressed
 * 2. IMMEDIATELY wake the screen back on â€” making power button appear "disabled"
 * 3. DETECT power menu windows via accessibility events and dismiss them instantly
 * 4. Lock Task mode blocks power menu UI from appearing (GLOBAL_ACTIONS disabled)
 * 5. Monitor ANY system dialog from power-related packages and kill them
 * 6. CONTINUOUS polling loop checks screen state every 500ms as backup
 * 
 * This works on ALL Android devices regardless of onKeyEvent() reliability.
 */
public class PowerButtonInterceptService extends AccessibilityService {

    private static final String TAG = "PowerBtnIntercept";
    
    private Handler handler;
    private BroadcastReceiver screenReceiver;
    private boolean isReceiverRegistered = false;
    private long lastScreenOffTime = 0;
    private int screenOffCount = 0;
    private PowerManager.WakeLock cachedScreenWakeLock; // Cached to prevent WakeLock leak
    
    // Track volume key state for recovery combo detection
    private boolean volumeUpPressed = false;
    private boolean volumeDownPressed = false;
    private long lastPowerKeyTime = 0;
    private int powerKeyPressCount = 0;
    
    // Screen monitor loop
    private Runnable screenMonitorRunnable;
    private boolean screenMonitorActive = false;
    
    // Power menu dialog keywords (multi-language)
    private static final Set<String> POWER_MENU_KEYWORDS = new HashSet<>(Arrays.asList(
        "power off", "power_off", "poweroff", "shut down", "shutdown", "switch off",
        "restart", "reboot", "recovery", "recovery mode", "safe mode", "safemode",
        "emergency", "emergency mode", "lockdown", "turn off",
        "global_actions", "power_dialog", "power_menu",
        "download mode", "odin mode", "fastboot",
        "à¦ªà¦¾à¦“à¦¯à¦¼à¦¾à¦° à¦…à¦«", "à¦°à¦¿à¦¸à§à¦Ÿà¦¾à¦°à§à¦Ÿ", "à¦¬à¦¨à§à¦§ à¦•à¦°à§à¦¨", "à¦ªà§à¦¨à¦°à¦¾à¦¯à¦¼ à¦šà¦¾à¦²à§",
        "à¤¬à¤‚à¤¦ à¤•à¤°à¥‡à¤‚", "à¤ªà¥à¤¨à¤ƒ à¤ªà¥à¤°à¤¾à¤°à¤‚à¤­", "à¤°à¥€à¤¸à¥à¤Ÿà¤¾à¤°à¥à¤Ÿ",
        "maintenance mode", "à¦°à¦•à§à¦·à¦£à¦¾à¦¬à§‡à¦•à§à¦·à¦£ à¦®à§‹à¦¡"
    ));
    
    // System packages that show power dialogs
    private static final Set<String> POWER_DIALOG_PACKAGES = new HashSet<>(Arrays.asList(
        "android",
        "com.android.systemui",
        "com.android.internal.app",
        "com.samsung.android.globalactions",
        "com.samsung.android.globalactions.bar",
        "com.samsung.android.app.routines",
        "com.samsung.android.sidekey",
        "com.miui.globalactions",
        "com.huawei.systemmanager",
        "com.coloros.globalactions",
        "com.oppo.globalactions",
        "com.oplus.globalactions",
        "com.oneplus.globalactions",
        "com.realme.globalactions",
        "com.vivo.globalactions",
        "com.android.server.telecom"
    ));

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "=== ULTIMATE Power Button Blocker CONNECTED ===");
        
        handler = new Handler(Looper.getMainLooper());

        // Configure for maximum interception capability
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 10; // React as fast as possible
        setServiceInfo(info);
        
        // *** CRITICAL: Register screen off/on receiver ***
        // This is the REAL power button detection mechanism
        registerScreenReceiver();
        
        // Start the continuous screen monitoring loop
        startScreenMonitor();
        
        Log.i(TAG, "Power button blocker FULLY ARMED â€” screen receiver + monitor active");
    }

    /**
     * THE KEY MECHANISM: Detect screen off/on broadcast events.
     * 
     * When user presses power button:
     * - Short press: screen turns off -> ACTION_SCREEN_OFF -> we wake it back up
     * - Long press: power menu appears -> detected via accessibility events -> we dismiss it
     * 
     * Result: Power button becomes COMPLETELY ineffective.
     */
    private void registerScreenReceiver() {
        if (isReceiverRegistered) return;
        
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                PreferenceManager prefs = new PreferenceManager(context);
                if (!prefs.isEnrolled() || prefs.isAppDisabled()) return;
                
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    long now = System.currentTimeMillis();
                    
                    // Track rapid screen-off events
                    if (now - lastScreenOffTime < 3000) {
                        screenOffCount++;
                    } else {
                        screenOffCount = 1;
                    }
                    lastScreenOffTime = now;
                    
                    Log.w(TAG, "=== SCREEN OFF (power button pressed) â€” FORCING BACK ON ===");
                    
                    // IMMEDIATELY wake the screen â€” fast staggered intervals
                    forceScreenOn();
                    handler.postDelayed(() -> forceScreenOn(), 50);
                    handler.postDelayed(() -> forceScreenOn(), 150);
                    handler.postDelayed(() -> forceScreenOn(), 400);
                    handler.postDelayed(() -> forceScreenOn(), 800);
                    handler.postDelayed(() -> forceScreenOn(), 1500);
                    
                    // Dismiss any power dialog that may have appeared
                    handler.postDelayed(() -> dismissPowerDialog(), 100);
                    handler.postDelayed(() -> dismissPowerDialog(), 500);
                    
                    // Verify lock task is still active
                    handler.postDelayed(() -> ensureLockTask(), 1500);
                    
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    Log.d(TAG, "Screen ON â€” checking for stale power dialogs");
                    
                    // Dismiss any surviving power dialog
                    handler.postDelayed(() -> dismissPowerDialog(), 200);
                    
                    // Verify lock task
                    handler.postDelayed(() -> ensureLockTask(), 500);
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        isReceiverRegistered = true;
        
        Log.i(TAG, "Screen off/on receiver REGISTERED");
    }
    
    /**
     * CONTINUOUS screen monitoring loop.
     * Checks screen state every 500ms and forces it on if it's off.
     * This is the BACKUP mechanism in case the broadcast is delayed.
     */
    private void startScreenMonitor() {
        if (screenMonitorActive) return;
        screenMonitorActive = true;
        
        screenMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!screenMonitorActive) return;
                
                try {
                    PreferenceManager prefs = new PreferenceManager(PowerButtonInterceptService.this);
                    if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        if (pm != null && !pm.isInteractive()) {
                            // Screen is off â€” force it back on
                            Log.w(TAG, "Screen monitor: screen is OFF â€” forcing ON");
                            forceScreenOn();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Screen monitor error", e);
                }
                
                // Re-schedule: check every 500ms
                handler.postDelayed(this, 500);
            }
        };
        
        handler.postDelayed(screenMonitorRunnable, 500);
        Log.i(TAG, "Screen monitor loop STARTED (500ms interval)");
    }
    
    /**
     * FORCE the screen to turn back on immediately.
     * Uses a cached WakeLock to prevent WakeLock leak on repeated calls.
     */
    @SuppressWarnings("deprecation")
    private void forceScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;

            // Release old if held
            if (cachedScreenWakeLock != null && cachedScreenWakeLock.isHeld()) {
                try { cachedScreenWakeLock.release(); } catch (Exception e) { /* ignore */ }
            }

            // Create or reuse
            if (cachedScreenWakeLock == null) {
                cachedScreenWakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                    "emilocker:pbi_force_on"
                );
            }
            cachedScreenWakeLock.acquire(15000); // Auto-release after 15s

        } catch (Exception e) {
            Log.e(TAG, "Error forcing screen on", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        PreferenceManager prefs = new PreferenceManager(this);
        if (!prefs.isEnrolled() || prefs.isAppDisabled()) return;
        
        int eventType = event.getEventType();
        CharSequence packageName = event.getPackageName();
        String pkgName = packageName != null ? packageName.toString() : "";
        
        // === DETECT POWER MENU WINDOWS ===
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // Check if from a known power dialog package
            if (POWER_DIALOG_PACKAGES.contains(pkgName) || isGlobalActionsPackage(pkgName)) {
                Log.w(TAG, "=== POWER DIALOG WINDOW from " + pkgName + " â€” DISMISSING ===");
                dismissPowerDialog();
                forceScreenOn();
                return;
            }
            
            // Deep-scan window content for power menu keywords
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    if (searchNodeForPowerMenu(root, 0)) {
                        Log.w(TAG, "=== POWER MENU CONTENT detected â€” DISMISSING ===");
                        dismissPowerDialog();
                        forceScreenOn();
                    }
                    root.recycle();
                }
            } catch (Exception e) { /* stale node */ }
        }
        
        // Check content changes in system packages
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (POWER_DIALOG_PACKAGES.contains(pkgName) || isGlobalActionsPackage(pkgName)) {
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        if (searchNodeForPowerMenu(root, 0)) {
                            Log.w(TAG, "=== POWER MENU CONTENT UPDATE â€” DISMISSING ===");
                            dismissPowerDialog();
                        }
                        root.recycle();
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }
        
        // Block clicks on power menu items
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null && isPowerMenuText(cs.toString().toLowerCase(Locale.ROOT))) {
                    Log.w(TAG, "=== BLOCKED click on power menu item: " + cs + " ===");
                    dismissPowerDialog();
                    return;
                }
            }
        }
    }
    
    /**
     * Check if package name is a GlobalActions variant (the power menu)
     */
    private boolean isGlobalActionsPackage(String pkgName) {
        String lower = pkgName.toLowerCase(Locale.ROOT);
        return lower.contains("globalactions") || lower.contains("global_actions") ||
               lower.contains("powermenu") || lower.contains("shutdownactivity") ||
               lower.contains("sidekey") || lower.contains("emergencymode") ||
               lower.contains("samsung.android.app.routines") ||
               lower.contains("maintenance");
    }
    
    /**
     * Recursively search accessibility nodes for power menu text
     */
    private boolean searchNodeForPowerMenu(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;
        
        try {
            CharSequence text = node.getText();
            if (text != null && isPowerMenuText(text.toString().toLowerCase(Locale.ROOT))) return true;
            
            CharSequence desc = node.getContentDescription();
            if (desc != null && isPowerMenuText(desc.toString().toLowerCase(Locale.ROOT))) return true;
            
            String viewId = node.getViewIdResourceName();
            if (viewId != null) {
                String vl = viewId.toLowerCase(Locale.ROOT);
                if (vl.contains("power") || vl.contains("reboot") || vl.contains("restart") || 
                    vl.contains("shutdown") || vl.contains("global_action") || vl.contains("recovery")) {
                    return true;
                }
            }
            
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    boolean found = searchNodeForPowerMenu(child, depth + 1);
                    child.recycle();
                    if (found) return true;
                }
            }
        } catch (Exception e) { /* stale nodes */ }
        
        return false;
    }
    
    private boolean isPowerMenuText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String keyword : POWER_MENU_KEYWORDS) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * AGGRESSIVELY dismiss the power dialog using multiple sequential actions
     */
    private void dismissPowerDialog() {
        try {
            // Rapid-fire BACK + HOME sequence to kill any dialog
            performGlobalAction(GLOBAL_ACTION_BACK);
            
            handler.postDelayed(() -> {
                try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Exception e) {}
            }, 50);
            
            handler.postDelayed(() -> {
                try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception e) {}
            }, 100);
            
            handler.postDelayed(() -> {
                try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Exception e) {}
            }, 200);
            
            handler.postDelayed(() -> {
                try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception e) {}
            }, 400);
            
            Log.i(TAG, "Power dialog dismiss sequence EXECUTED");
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing power dialog", e);
        }
    }
    
    /**
     * Ensure Lock Task mode is active (blocks power menu UI)
     */
    private void ensureLockTask() {
        try {
            if (!LockTaskBootActivity.isLockTaskActive(this)) {
                Log.w(TAG, "Lock task NOT active â€” re-launching");
                LockTaskBootActivity.forceRelaunch(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking lock task", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted â€” will reconnect");
    }

    /**
     * onKeyEvent â€” secondary/bonus defense layer.
     * 
     * NOTE: KEYCODE_POWER is NOT reliably delivered here on most devices.
     * The primary defense is the screen off/on receiver + monitor loop above.
     * This is kept for the devices where it DOES work.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        PreferenceManager prefs = new PreferenceManager(this);
        if (!prefs.isEnrolled() || prefs.isAppDisabled()) return false;

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        
        // === VOLUME KEY TRACKING for recovery combo ===
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPressed = (action == KeyEvent.ACTION_DOWN);
            if (volumeUpPressed && isRecoveryCombo()) {
                Log.w(TAG, "RECOVERY COMBO (Vol Up + Power) BLOCKED");
                dismissPowerDialog();
                return true;
            }
        }
        
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPressed = (action == KeyEvent.ACTION_DOWN);
            if (volumeDownPressed && isRecoveryCombo()) {
                Log.w(TAG, "SAFE MODE COMBO (Vol Down + Power) BLOCKED");
                dismissPowerDialog();
                return true;
            }
        }

        // === POWER BUTTON â€” Block if delivered here ===
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            if (action == KeyEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastPowerKeyTime < 1000) {
                    powerKeyPressCount++;
                } else {
                    powerKeyPressCount = 1;
                }
                lastPowerKeyTime = now;
                
                Log.w(TAG, "POWER KEY DOWN â€” BLOCKING (press #" + powerKeyPressCount + ")");
                
                dismissPowerDialog();
                handler.postDelayed(() -> forceScreenOn(), 100);
                
                return true; // CONSUME
            }
            
            if (action == KeyEvent.ACTION_UP) {
                forceScreenOn();
                return true; // CONSUME
            }
        }

        // === SAMSUNG BIXBY KEY (KEYCODE_ASSIST = 219) ===
        // Samsung S8/S9 use Bixby + Volume combos for Download/Recovery mode
        if (keyCode == 219 || keyCode == KeyEvent.KEYCODE_ASSIST) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (volumeUpPressed || volumeDownPressed) {
                    Log.w(TAG, "BIXBY + VOLUME COMBO BLOCKED (Samsung Recovery/Download Mode)");
                    dismissPowerDialog();
                    forceScreenOn();
                    return true; // CONSUME
                }
                // Block Bixby key entirely when volume was recently pressed
                long now = System.currentTimeMillis();
                if (now - lastPowerKeyTime < 3000) {
                    Log.w(TAG, "BIXBY + RECENT POWER BLOCKED");
                    return true;
                }
            }
        }

        // === Samsung Side Key (may map to different keycodes on newer Samsung) ===
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH && action == KeyEvent.ACTION_DOWN) {
            if (volumeUpPressed || volumeDownPressed) {
                Log.w(TAG, "APP_SWITCH + VOLUME combo BLOCKED (potential Samsung bypass)");
                dismissPowerDialog();
                return true;
            }
        }

        return false;
    }
    
    private boolean isRecoveryCombo() {
        long now = System.currentTimeMillis();
        return (now - lastPowerKeyTime < 2000) && (volumeUpPressed || volumeDownPressed);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "=== PowerButtonInterceptService DESTROYED ===");
        
        // Stop screen monitor
        screenMonitorActive = false;
        
        // Unregister receiver
        if (isReceiverRegistered && screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception e) {}
            isReceiverRegistered = false;
        }
        
        // Try to restart the service
        try {
            PreferenceManager prefs = new PreferenceManager(this);
            if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                DeviceProtectionManager pm = new DeviceProtectionManager(this);
                pm.enablePowerButtonInterceptService();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restarting accessibility service", e);
        }
    }
}
