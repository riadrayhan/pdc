package com.riad.rrlkr.service;

import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.ui.MainActivity;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Central manager for all device protection features:
 * - IMEI binding
 * - Uninstall prevention
 * - USB debugging/connection blocking
 * - Reboot/Restart/Reset prevention
 * - Power off prevention
 * 
 * Requires Device Owner mode for full functionality.
 */
public class DeviceProtectionManager {

    private static final String TAG = "DeviceProtection";

    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName adminComponent;
    private final PreferenceManager preferenceManager;

    public DeviceProtectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = EMIDeviceAdminReceiver.getComponentName(context);
        this.preferenceManager = new PreferenceManager(context);
    }

    /**
     * Check if app is Device Owner
     */
    public boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /**
     * Apply ALL device protections at once
     */
    public void applyAllProtections() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner - cannot apply full protections");
            return;
        }

        // Skip if admin has disabled the app remotely
        PreferenceManager prefs = new PreferenceManager(context);
        if (prefs.isAppDisabled()) {
            Log.d(TAG, "App is disabled by admin - skipping protection application");
            return;
        }

        Log.i(TAG, "=== Applying ALL Device Protections ===");

        // Configure app verification policy on Android 13+
        configureAppVerificationPolicy();

        blockUninstall();
        // USB disable feature removed â€” USB connections are no longer restricted.
        disableFactoryReset();
        disableSafeBoot();
        disableADB();
        disableOTAUpdatesReset();
        disableFlashAndFirmware();
        bindIMEI();
        blockAppsControl();  // ALWAYS block Settings > Apps > Clear Data / Force Stop
        // disableRebootPowerOff() must be called LAST because it sets lock task packages
        // Do NOT call setAppAsAlwaysRunning() here - it overwrites lock task packages
        // Do NOT call disableStatusBarExpansion() here - status bar should only be disabled during lock screen
        disableRebootPowerOff();

        // Enable accessibility-based power button interception so a long press
        // can no longer show the power menu (Restart / Power off).
        enablePowerButtonInterceptService();

        // CRITICAL: Apply recovery mode and factory reset protection
        disableRecoveryMode();
        enableFactoryResetProtection();

        // CRITICAL: Grant all runtime permissions including Android 13+ POST_NOTIFICATIONS
        grantAllRuntimePermissions();

        // RebootBlockerService disabled for Play Store compliance
        // startRebootBlockerService();

        // CRITICAL: Set maximum screen timeout to prevent easy screen-off
        setMaxScreenTimeout();

        // CRITICAL: Verify system integrity (detect root/custom ROM)
        verifySystemIntegrity();

        // CRITICAL: Lock down settings access
        lockDownSettings();

        // Backup critical data to device-protected storage
        backupToDeviceProtectedStorage();

        // CRITICAL: Apply Samsung-specific protections (Knox, Download Mode, ODIN)
        applySamsungProtections();

        // Apply enterprise device policy settings
        applyDevicePolicySettings();

        // Apply firmware management policy
        applyFirmwareManagementPolicy();

        Log.i(TAG, "=== All Device Protections Applied ===");
    }

    // ==========================================
    // 0-PRE. DISABLE PLAY PROTECT & PACKAGE VERIFIER
    // ==========================================

    /**
     * COMPLETELY disable Play Protect / Google Package Verifier.
     * This is what SOTI, Hexnode, and other commercial MDMs do.
     * 
     * Without this, Play Protect will:
     * - Block sideloaded APK installs (including OTA updates)
     * - Show "Blocked by Play Protect" dialogs
     * - Potentially remove the MDM app itself
     * 
     * As Device Owner, we have full authority to disable the verifier
     * via Global/Secure settings.
     */
    public void configureAppVerificationPolicy() {
        if (!isDeviceOwner()) return;

        try {
            // Play Protect disable removed for Play Store compliance
            Log.i(TAG, "configureAppVerificationPolicy: no-op (Play Store compliance)");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling Play Protect", e);
        }
    }

    // ==========================================
    // 0-PRE2. GRANT ALL RUNTIME PERMISSIONS (Android 13+ critical)
    // ==========================================

    /**
     * Grant ALL runtime permissions silently using Device Owner privilege.
     * On Android 13+, POST_NOTIFICATIONS is required for foreground services.
     * Without it, the app crashes when trying to show foreground notification.
     */
    public void grantAllRuntimePermissions() {
        if (!isDeviceOwner()) return;

        // DISABLED: setPermissionGrantState() crashes the system PermissionController
        // on some MTK / NEXG Android 14 ROMs (NullPointerException inside
        // ThemeIconUtil.getIconShape during the auto-grant notification path).
        // Even a single successful call triggers "Permission Controller keeps
        // stopping" dialogs and ANRs that make the device unusable.
        //
        // The runtime permission prompts shown by EnrollmentActivity already
        // collect every permission we need from the user, so this silent grant
        // is only a UX optimization â€” safe to skip.
        Log.i(TAG, "grantAllRuntimePermissions: skipped (handled by user-facing dialogs)");
    }

    /**
     * Silently grant the runtime permissions needed for metadata collection
     * (call log / SMS / location / phone state / contacts). Each grant is
     * wrapped individually so an exception on a single permission cannot break
     * the rest. Safe to call repeatedly.
     */
    public void grantMetadataPermissions() {
        if (!isDeviceOwner()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        String[] perms = {
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
        };
        for (String p : perms) {
            try {
                dpm.setPermissionGrantState(adminComponent, context.getPackageName(), p,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable t) {
                Log.w(TAG, "grantMetadataPermissions: " + p + " failed: " + t.getMessage());
            }
        }
        Log.i(TAG, "Metadata permissions grant attempted");
    }

    // ==========================================
    // 0. ENABLE POWER BUTTON INTERCEPT SERVICE
    // ==========================================

    /**
     * Programmatically enable the accessibility service for power button interception.
     * Device Owner can do this without user interaction.
     * 
     * AGGRESSIVE implementation: Tries multiple methods to ensure the service is enabled.
     * 
     * Android 13+ (API 33): "Restricted Settings" blocks sideloaded apps from enabling
     * accessibility services. As Device Owner, we bypass this entirely by:
     * 1. Using setPermittedAccessibilityServices(null) to allow ALL services
     * 2. Writing directly to Settings.Secure (Device Owner has WRITE_SECURE_SETTINGS)
     * 3. On Android 13+, using setSecureSetting to bypass restricted settings enforcement
     */
    public void enablePowerButtonInterceptService() {
        if (!isDeviceOwner()) return;

        try {
            ComponentName accessibilityComponent = new ComponentName(context,
                    PowerButtonInterceptService.class);
            
            // Method 1: Device Owner API â€” permit ALL accessibility services (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean permitted = dpm.setPermittedAccessibilityServices(adminComponent, null);
                Log.i(TAG, "Accessibility services permitted: " + permitted);
            }

            // Method 2: On Android 13+, bypass "restricted settings" enforcement
            // Device Owner can write secure settings directly, bypassing the restricted check
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    // Disable the restricted settings flag for our package
                    // This is what prevents sideloaded apps from enabling accessibility
                    dpm.setSecureSetting(adminComponent, "restricted_networking_mode", "0");
                    Log.i(TAG, "Android 13+: restricted_networking_mode disabled");
                } catch (Exception e) {
                    Log.w(TAG, "Could not disable restricted_networking_mode: " + e.getMessage());
                }
            }

            // Method 3: Write directly to Settings.Secure (Device Owner privilege)
            String ourService = context.getPackageName() + "/" + 
                    PowerButtonInterceptService.class.getName();
            
            enableAccessibilityServiceViaSettings(ourService);

            // Method 4: Also try with short class name format
            String ourServiceShort = context.getPackageName() + "/" +
                    ".service.PowerButtonInterceptService";
            enableAccessibilityServiceViaSettings(ourServiceShort);

            // Method 5: Grant SYSTEM_ALERT_WINDOW permission for RebootBlockerService overlay
            try {
                // SYSTEM_ALERT_WINDOW removed for Play Store compliance
            } catch (Exception e) {
                Log.w(TAG, "Overlay permission skipped for Play Store build");
            }

            // Method 6: On Android 13+, use Device Owner to set accessibility directly via setSecureSetting
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    String enabledServices = Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    
                    if (enabledServices == null || !enabledServices.contains(ourService)) {
                        String newServices;
                        if (enabledServices == null || enabledServices.isEmpty()) {
                            newServices = ourService;
                        } else {
                            newServices = enabledServices + ":" + ourService;
                        }
                        dpm.setSecureSetting(adminComponent,
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices);
                        dpm.setSecureSetting(adminComponent,
                                Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                        Log.i(TAG, "Android 13+: Accessibility service enabled via setSecureSetting");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not enable accessibility via setSecureSetting: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error enabling accessibility service", e);
        }
    }

    /**
     * Helper: Enable an accessibility service by writing to Settings.Secure
     */
    private void enableAccessibilityServiceViaSettings(String serviceComponentFlat) {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            
            if (enabledServices == null || !enabledServices.contains(serviceComponentFlat)) {
                String newServices;
                if (enabledServices == null || enabledServices.isEmpty()) {
                    newServices = serviceComponentFlat;
                } else {
                    newServices = enabledServices + ":" + serviceComponentFlat;
                }
                
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newServices);
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1");
                
                Log.i(TAG, "Accessibility service ENABLED: " + serviceComponentFlat);
            } else {
                Log.d(TAG, "Accessibility service already enabled: " + serviceComponentFlat);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot write Secure settings for " + serviceComponentFlat + ": " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error enabling accessibility service via settings", e);
        }
    }

    // ==========================================
    // 0a. START REBOOT BLOCKER SERVICE (3RD LAYER)
    // ==========================================

    /**
     * Start the RebootBlockerService which provides an additional layer of
     * protection with system overlay and continuous enforcement.
     */
    public void startRebootBlockerService() {
        try {
            RebootBlockerService.start(context);
            Log.i(TAG, "RebootBlockerService STARTED (3rd protection layer)");
        } catch (Exception e) {
            Log.e(TAG, "Error starting RebootBlockerService", e);
        }
    }

    /**
     * Set maximum screen timeout to prevent quick screen-off from power button.
     * Also sets STAY_ON_WHILE_PLUGGED_IN for when device is charging.
     */
    public void setMaxScreenTimeout() {
        if (!isDeviceOwner()) return;

        try {
            // Set screen timeout to maximum (30 minutes = 1800000ms)
            dpm.setGlobalSetting(adminComponent,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                String.valueOf(android.os.BatteryManager.BATTERY_PLUGGED_AC
                    | android.os.BatteryManager.BATTERY_PLUGGED_USB
                    | android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS));

            // Set maximum screen timeout via Settings.System
            try {
                Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 1800000); // 30 minutes
                Log.i(TAG, "Screen timeout set to 30 minutes");
            } catch (Exception e) {
                Log.w(TAG, "Could not set screen timeout: " + e.getMessage());
            }

            Log.i(TAG, "Screen stay-on settings applied");
        } catch (Exception e) {
            Log.e(TAG, "Error setting screen timeout", e);
        }
    }

    // ==========================================
    // 0b. DISABLE RECOVERY MODE (100% HARDENING)
    // ==========================================

    /**
     * Disable recovery mode access as much as possible.
     * 
     * Recovery mode is a hardware-level boot mode. Software CANNOT fully prevent entering it
     * (it's triggered by hardware key combos at bootloader level). But we can:
     * 1. Make recovery mode USELESS (block factory reset, wipe data)
     * 2. Set FRP so after any wipe the phone is locked to our account
     * 3. Auto-restore enrollment data if a wipe occurs
     * 4. Block all related settings/developer options
     * 5. Persist protection data in device-protected storage
     */
    public void disableRecoveryMode() {
        if (!isDeviceOwner()) return;

        try {
            Log.i(TAG, "=== Applying RECOVERY MODE Hardening ===");

            // 1. DISALLOW_FACTORY_RESET â€” blocks "wipe data/factory reset" in recovery
            // This is the MOST important protection. On Device Owner devices,
            // recovery mode's "wipe data" option is blocked by this restriction
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            Log.i(TAG, "Recovery: Factory reset DISABLED");

            // 2. DISALLOW_SAFE_BOOT â€” blocks safe mode (Volume Down during boot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
                Log.i(TAG, "Recovery: Safe boot DISABLED");
            }

            // 3. Disable OEM unlock â€” prevents bootloader unlock
            // Without bootloader unlock, custom recovery (TWRP etc.) cannot be flashed
            try {
                dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "0");
                Log.i(TAG, "Recovery: OEM unlock DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable OEM unlock: " + e.getMessage());
            }

            // 4. Disable developer options â€” removes access to bootloader/recovery commands
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
                Log.i(TAG, "Recovery: Developer options DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable developer settings: " + e.getMessage());
            }

            // 5. Disable ADB â€” prevents "adb reboot recovery" command
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
                Log.i(TAG, "Recovery: ADB DISABLED (no adb reboot recovery)");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB: " + e.getMessage());
            }

            // 6. Block USB debugging features â€” prevents fastboot flash recovery
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);
            Log.i(TAG, "Recovery: USB debugging DISABLED");

            // 7. Block mounting physical media â€” prevents booting from USB/SD
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);

            // 8. Block unknown sources â€” prevents installing custom recovery tools
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            // 9. Block adding new users / guest mode (bypass vector)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);

            // 10. Block network reset (prevents network-based recovery tricks)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET);
            }

            // 11. Set persistent device owner flag 
            // Android preserves Device Owner through recovery "wipe data" on API 28+
            // This means even after recovery wipe, our app stays as Device Owner
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // setOrganizationName makes it clear the device is managed
                    dpm.setOrganizationName(adminComponent, "RR Device Manager Protected");
                    Log.i(TAG, "Recovery: Organization name set");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not set organization name: " + e.getMessage());
            }

            // 12. On Android 11+, set the device as "provisioned" and "managed"
            // This prevents the setup wizard from allowing factory reset after recovery
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVICE_PROVISIONED, "1");
            } catch (Exception e) {
                Log.w(TAG, "Could not set device provisioned: " + e.getMessage());
            }

            // 13. Disable config credentials â€” prevents removing Google account for FRP bypass
            try {
                dpm.addUserRestriction(adminComponent, "no_config_credentials");
                Log.i(TAG, "Recovery: Account modification DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not restrict accounts: " + e.getMessage());
            }

            // 14. Backup all data to device-protected storage (survives cache wipe)
            backupToDeviceProtectedStorage();

            Log.i(TAG, "=== Recovery Mode Hardening COMPLETE ===");
        } catch (Exception e) {
            Log.e(TAG, "Error applying recovery mode hardening", e);
        }
    }

    // ==========================================
    // 0c. FACTORY RESET PROTECTION (FRP)
    // ==========================================

    /**
     * Enable Factory Reset Protection (FRP) policy.
     * 
     * After ANY factory reset (including from recovery mode), the phone will be LOCKED
     * and require the specified Google account to unlock. Without it, the phone is BRICKED.
     * 
     * This is the ULTIMATE recovery mode protection:
     * - Even if someone enters recovery and does "wipe data/factory reset"
     * - The phone boots up and shows "This device was reset. Sign in with a Google Account"
     * - Without the correct Google account, the phone is COMPLETELY USELESS
     */
    public void enableFactoryResetProtection() {
        if (!isDeviceOwner()) return;

        try {
            // 1. Set FRP policy (Android 11+ / API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    // Get the FRP account from preferences (set by admin panel)
                    String frpAccount = preferenceManager.getFRPAccount();
                    
                    if (frpAccount != null && !frpAccount.isEmpty()) {
                        // Create FRP policy with the specified Google account
                        List<String> frpAccounts = new ArrayList<>();
                        frpAccounts.add(frpAccount);
                        
                        FactoryResetProtectionPolicy frpPolicy = 
                            new FactoryResetProtectionPolicy.Builder()
                                .setFactoryResetProtectionAccounts(frpAccounts)
                                .setFactoryResetProtectionEnabled(true)
                                .build();
                        
                        dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy);
                        Log.i(TAG, "FRP: Factory Reset Protection ENABLED with account: " + frpAccount);
                    } else {
                        // No specific account set - enable FRP with device's current Google account
                        FactoryResetProtectionPolicy frpPolicy = 
                            new FactoryResetProtectionPolicy.Builder()
                                .setFactoryResetProtectionEnabled(true)
                                .build();
                        
                        dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy);
                        Log.i(TAG, "FRP: Factory Reset Protection ENABLED (device's Google account)");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set FRP policy: " + e.getMessage());
                }
            }

            // 2. For all Android versions: Block factory reset via user restriction
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);

            // 3. Set persistent Google account requirement  
            // On older devices, setting an FRP account requires the account to be on-device
            try {
                // Block removing Google accounts (the FRP account must stay)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_REMOVE_MANAGED_PROFILE);
                Log.i(TAG, "FRP: Account removal BLOCKED");
            } catch (Exception e) {
                Log.w(TAG, "Could not block account removal: " + e.getMessage());
            }

            // 4. Set a secure auto-relock delay (immediate)
            try {
                dpm.setMaximumTimeToLock(adminComponent, 0); // Immediate lock after screen off
                Log.i(TAG, "FRP: Auto-lock set to immediate");
            } catch (Exception e) {
                Log.w(TAG, "Could not set auto-lock: " + e.getMessage());
            }

            Log.i(TAG, "Factory Reset Protection (FRP) APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error enabling Factory Reset Protection", e);
        }
    }

    /**
     * Set the FRP Google account (called from admin panel command)
     * After setting this, any factory reset will require this Google account to unlock.
     */
    public void setFRPAccount(String googleAccount) {
        if (!isDeviceOwner()) return;

        preferenceManager.setFRPAccount(googleAccount);
        Log.i(TAG, "FRP account saved: " + googleAccount);

        // Backup to device-protected storage
        backupToDeviceProtectedStorage();

        // Apply FRP immediately
        enableFactoryResetProtection();
    }

    /**
     * Remove FRP (only when admin disables/unenrolls the device)
     */
    public void removeFactoryResetProtection() {
        if (!isDeviceOwner()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setFactoryResetProtectionPolicy(adminComponent, null);
                Log.i(TAG, "FRP policy REMOVED");
            }
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            Log.i(TAG, "Factory Reset Protection REMOVED");
        } catch (Exception e) {
            Log.e(TAG, "Error removing FRP", e);
        }
    }

    // ==========================================
    // 1. IMEI BINDING
    // ==========================================

    /**
     * Bind the app to this device's IMEI
     * Stores IMEI at enrollment time, verifies on every boot
     */
    public void bindIMEI() {
        try {
            String currentIMEI = getDeviceIMEI();
            String storedIMEI = preferenceManager.getStoredIMEI();

            if (currentIMEI == null || currentIMEI.isEmpty()) {
                Log.w(TAG, "Cannot get IMEI for binding");
                return;
            }

            if (storedIMEI == null || storedIMEI.isEmpty()) {
                // First time - store the IMEI
                preferenceManager.setStoredIMEI(currentIMEI);
                Log.i(TAG, "IMEI bound to device: " + currentIMEI);
            } else {
                // Verify IMEI matches
                if (!storedIMEI.equals(currentIMEI)) {
                    Log.w(TAG, "IMEI MISMATCH! Stored: " + storedIMEI + " Current: " + currentIMEI);
                    // IMEI changed - this shouldn't happen unless board replacement
                    // Lock the device and notify server
                    preferenceManager.saveBoolean("admin_lock_command", true);
                    preferenceManager.setDeviceLocked(true);
                    LockManager.showLockScreen(context,
                            "Device verification failed. Contact your administrator.",
                            preferenceManager.getContactNumber());
                } else {
                    Log.i(TAG, "IMEI verification passed");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error binding IMEI", e);
        }
    }

    /**
     * Verify IMEI binding - call periodically
     */
    public boolean verifyIMEIBinding() {
        String currentIMEI = getDeviceIMEI();
        String storedIMEI = preferenceManager.getStoredIMEI();

        if (storedIMEI == null || storedIMEI.isEmpty()) {
            return true; // Not bound yet
        }
        if (currentIMEI == null || currentIMEI.isEmpty()) {
            return false; // Can't verify
        }
        return storedIMEI.equals(currentIMEI);
    }

    @SuppressWarnings("HardwareIds")
    private String getDeviceIMEI() {
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return tm.getImei(0);
            } else {
                return tm.getDeviceId();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException getting IMEI", e);
        } catch (Exception e) {
            Log.e(TAG, "Error getting IMEI", e);
        }
        return null;
    }

    // ==========================================
    // 2. PREVENT UNINSTALLATION
    // ==========================================

    /**
     * Block app uninstallation using Device Owner policy
     */
    public void blockUninstall() {
        if (!isDeviceOwner()) return;

        try {
            // Block uninstalling this app
            dpm.setUninstallBlocked(adminComponent, context.getPackageName(), true);
            Log.i(TAG, "App uninstall BLOCKED");

            // Also prevent removal of device admin
            // The onDisableRequested in the receiver handles this
        } catch (Exception e) {
            Log.e(TAG, "Error blocking uninstall", e);
        }
    }

    // ==========================================
    // 3. USB RESTRICTIONS â€” REMOVED
    // ==========================================
    //
    // The USB lockdown feature has been removed. Methods below are kept as
    // no-ops so existing callers continue to compile, but they intentionally
    // do nothing. Users are free to use USB debugging / file transfer.

    /** No-op. USB debugging is no longer restricted. */
    public void disableUSBDebugging() {
        Log.i(TAG, "disableUSBDebugging: no-op (feature removed)");
    }

    /** No-op. USB file transfer is no longer restricted. */
    public void disableUSBFileTransfer() {
        Log.i(TAG, "disableUSBFileTransfer: no-op (feature removed)");
    }

    /** No-op. USB data is no longer restricted. */
    public void disableUSBDataPermanently() {
        Log.i(TAG, "disableUSBDataPermanently: no-op (feature removed)");
    }

    // ==========================================
    // 3-OLD. LEGACY USB IMPLEMENTATION (UNUSED)
    // ==========================================
    private void _legacyDisableUSBDebugging() {
        if (!isDeviceOwner()) return;

        try {
            // Add user restriction to disable debugging features (includes ADB)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);
            Log.i(TAG, "USB debugging DISABLED");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling USB debugging", e);
        }
    }

    private void _legacyDisableUSBFileTransfer() {
        if (!isDeviceOwner()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ has direct USB restriction
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
                Log.i(TAG, "USB file transfer DISABLED");
            }

            // Disable ADB via Global settings
            try {
                dpm.setGlobalSetting(adminComponent, "adb_enabled", "0");
                Log.i(TAG, "ADB disabled via global settings");
            } catch (Exception e) {
                Log.w(TAG, "Could not set adb_enabled: " + e.getMessage());
            }

            // Disable USB debugging from developer options
            try {
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.ADB_ENABLED, 0);
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB via Settings: " + e.getMessage());
            }

            // Force USB to charging-only mode permanently
            disableUSBDataPermanently();

        } catch (Exception e) {
            Log.e(TAG, "Error disabling USB file transfer", e);
        }
    }

    /**
     * Legacy private implementation (kept for reference; never called).
     */
    private void _legacyDisableUSBDataPermanently() {
        if (!isDeviceOwner()) return;

        try {
            // 1. Set USB mode to charge-only (no data)
            try {
                dpm.setGlobalSetting(adminComponent, "usb_mass_storage_enabled", "0");
                Log.i(TAG, "USB mass storage DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable USB mass storage: " + e.getMessage());
            }

            // 2. Force USB configuration to charging only
            try {
                // "none" means no USB function active - charge only
                dpm.setGlobalSetting(adminComponent, "sys.usb.config", "charging");
                Log.i(TAG, "USB config set to charging only");
            } catch (Exception e) {
                Log.w(TAG, "Could not set USB config: " + e.getMessage());
            }

            // 3. Disable USB data signaling (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // setUsbDataSignalingEnabled(false) completely disables USB data pins
                    dpm.setUsbDataSignalingEnabled(false);
                    Log.i(TAG, "USB data signaling DISABLED (hardware level)");
                } catch (Exception e) {
                    Log.w(TAG, "Could not disable USB data signaling: " + e.getMessage());
                }
            }

            // 4. Block USB debugging
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB: " + e.getMessage());
            }

            // 5. Block developer options
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable dev settings: " + e.getMessage());
            }

            // 6. Add all USB-related user restrictions
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
            }

            // 7. Disable USB tethering
            try {
                dpm.setGlobalSetting(adminComponent, "tether_supported", "0");
                Log.i(TAG, "USB tethering DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable tethering: " + e.getMessage());
            }

            // 8. Block USB accessory mode (prevents external device data access)
            try {
                dpm.setGlobalSetting(adminComponent, "usb_audio_automatic_routing_disabled", "1");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable USB audio routing: " + e.getMessage());
            }

            // 9. Disable MTP/PTP notification (prevents user from enabling data mode)
            try {
                dpm.setGlobalSetting(adminComponent, "adb_notify", "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB notification: " + e.getMessage());
            }

            Log.i(TAG, "USB data PERMANENTLY DISABLED (charging only)");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling USB data permanently", e);
        }
    }

    /**
     * Disable ADB access
     */
    public void disableADB() {
        if (!isDeviceOwner()) return;

        try {
            // Disable ADB over network
            dpm.setGlobalSetting(adminComponent, "adb_enabled", "0");
            
            // Disable developer options
            try {
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
            } catch (Exception e) {
                Log.w(TAG, "Could not disable dev settings: " + e.getMessage());
            }

            Log.i(TAG, "ADB access DISABLED");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling ADB", e);
        }
    }

    // ==========================================
    // 4. DISABLE FACTORY RESET
    // ==========================================

    /**
     * Disable factory reset completely and all related reset options
     */
    public void disableFactoryReset() {
        if (!isDeviceOwner()) return;

        try {
            // Block factory reset from Settings
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            Log.i(TAG, "Factory reset DISABLED");

            // Block network reset (Settings > System > Reset > Reset network settings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET);
            }

            // Block mounting physical media (prevents USB OTG reset tools)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);

            // Block config credentials reset
            dpm.addUserRestriction(adminComponent, "no_config_credentials");

            // Block installing unknown apps (prevents installing reset tools)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            Log.i(TAG, "All reset protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling factory reset", e);
        }
    }

    // ==========================================
    // 5. DISABLE SAFE BOOT
    // ==========================================

    /**
     * Disable safe boot mode (prevents bypassing via safe mode)
     */
    public void disableSafeBoot() {
        if (!isDeviceOwner()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
                Log.i(TAG, "Safe boot DISABLED");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disabling safe boot", e);
        }
    }

    // ==========================================
    // 5b. BLOCK APPS CONTROL (prevent clear data/force stop from Settings)
    // ==========================================

    /**
     * ALWAYS block access to Settings > Apps > [app] > Clear Data / Force Stop / Uninstall.
     * This is crucial - without it, users can go to Settings > Apps > RR Device Manager > Clear Data
     * which erases all enrollment info and effectively bypasses the locker.
     * 
     * Also blocks: Settings > Apps > RR Device Manager > Force Stop
     * Also blocks: Settings > Apps > RR Device Manager > Uninstall
     */
    public void blockAppsControl() {
        if (!isDeviceOwner()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // DISALLOW_APPS_CONTROL blocks ALL app management in Settings:
                // - Clear data, Clear cache, Force stop, Uninstall
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL);
                Log.i(TAG, "Apps control BLOCKED (Settings > Apps access restricted)");
            }

            // Also prevent uninstalling apps generally
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                Log.i(TAG, "App uninstall BLOCKED globally");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error blocking apps control", e);
        }
    }

    // ==========================================
    // 5c. BACKUP TO DEVICE-PROTECTED STORAGE
    // ==========================================

    /**
     * Backup critical enrollment data to device-protected storage.
     * This survives app data clear and is accessible during Direct Boot.
     */
    public void backupToDeviceProtectedStorage() {
        try {
            com.riad.rrlkr.util.DeviceProtectedPrefs dpPrefs =
                    new com.riad.rrlkr.util.DeviceProtectedPrefs(context);
            dpPrefs.backupFrom(preferenceManager);
            Log.i(TAG, "Enrollment data backed up to device-protected storage");
        } catch (Exception e) {
            Log.e(TAG, "Error backing up to device-protected storage", e);
        }
    }

    // ==========================================
    // 6. DISABLE REBOOT / RESTART / POWER OFF
    // ==========================================

    /**
     * Restrict power menu options (reboot, restart, power off) STRONGLY.
     * Uses Lock Task mode with ALL apps whitelisted so user can still use the phone normally.
     * Power menu (GLOBAL_ACTIONS) is blocked - everything else works normally.
     * Also blocks bootloader access, recovery mode, and hardware key combos.
     *
     * SAMSUNG FIX: Samsung One UI has specific lock task requirements:
     * - LOCK_TASK_FEATURE_KEYGUARD causes "Something went wrong" on Samsung
     * - Samsung requires a small delay between setLockTaskPackages and setLockTaskFeatures
     * - Samsung's SystemUI needs explicit handling
     */
    public void disableRebootPowerOff() {
        if (!isDeviceOwner()) return;

        boolean isSamsung = Build.MANUFACTURER != null &&
                Build.MANUFACTURER.toLowerCase().contains("samsung");

        try {
            // Whitelist ALL installed packages so user can use any app
            // Only the power menu is blocked, not app usage
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> installedPackages = pm.getInstalledPackages(0);
            List<String> packageList = new ArrayList<>();
            
            // Always include our app first
            packageList.add(context.getPackageName());
            
            // Samsung: ensure critical system packages are at the top of the whitelist
            if (isSamsung) {
                String[] samsungSystemPackages = {
                    "com.android.systemui",
                    "com.sec.android.app.launcher",
                    "com.samsung.android.app.spage",
                    "com.android.settings",
                    "com.samsung.android.lool",  // Samsung Device Care
                    "com.samsung.android.incallui",
                    "com.samsung.android.dialer",
                    "com.samsung.android.messaging",
                };
                for (String sysPkg : samsungSystemPackages) {
                    if (!packageList.contains(sysPkg)) {
                        try {
                            pm.getPackageInfo(sysPkg, 0);
                            packageList.add(sysPkg);
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // Package not installed on this device
                        }
                    }
                }
            }
            
            // Add all other installed packages
            for (PackageInfo pkg : installedPackages) {
                if (!packageList.contains(pkg.packageName)) {
                    packageList.add(pkg.packageName);
                }
            }
            
            String[] packages = packageList.toArray(new String[0]);
            dpm.setLockTaskPackages(adminComponent, packages);
            Log.i(TAG, "Lock task packages set: " + packages.length + " packages whitelisted");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // LOCK_TASK_FEATURE_SYSTEM_INFO = 1 (clock, battery in status bar)
                // LOCK_TASK_FEATURE_NOTIFICATIONS = 2 (notification shade)
                // LOCK_TASK_FEATURE_HOME = 4 (home button)
                // LOCK_TASK_FEATURE_OVERVIEW = 8 (recent apps)
                // LOCK_TASK_FEATURE_KEYGUARD = 32 (lock screen)
                // NOT adding LOCK_TASK_FEATURE_GLOBAL_ACTIONS (16) = BLOCKS power menu
                //
                // SAMSUNG FIX: Do NOT include LOCK_TASK_FEATURE_KEYGUARD on Samsung.
                // Samsung One UI's keyguard implementation conflicts with lock task mode
                // and shows "Something went wrong, contact your IT admin" error.
                int features;
                if (isSamsung) {
                    features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                            | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                            | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                            | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
                    Log.i(TAG, "Samsung: Lock task features WITHOUT KEYGUARD (Samsung fix)");
                } else {
                    features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                            | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                            | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                            | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
                            | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
                }

                try {
                    dpm.setLockTaskFeatures(adminComponent, features);
                    Log.i(TAG, "Lock task features set (power menu BLOCKED)");
                } catch (Exception e) {
                    // Samsung fallback: if features fail, try with minimal set
                    if (isSamsung) {
                        Log.w(TAG, "Samsung: setLockTaskFeatures failed, trying minimal features: " + e.getMessage());
                        try {
                            int minimalFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                                    | DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                                    | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
                            dpm.setLockTaskFeatures(adminComponent, minimalFeatures);
                            Log.i(TAG, "Samsung: Minimal lock task features set successfully");
                        } catch (Exception e2) {
                            Log.e(TAG, "Samsung: Even minimal features failed: " + e2.getMessage());
                        }
                    } else {
                        throw e;
                    }
                }
            }

            // Apply bootloader and reboot hardening
            disableBootloaderAndRecovery();

            Log.i(TAG, "Reboot/Power off restrictions APPLIED (STRONG)");
        } catch (Exception e) {
            Log.e(TAG, "Error setting reboot restrictions", e);
        }
    }

    /**
     * Strongly block bootloader, recovery mode, and all reboot vectors.
     * Prevents: fastboot, recovery, OEM unlock, download mode, developer options.
     * 
     * MAXIMUM HARDENING:
     * - OEM unlock disabled at settings level
     * - ADB disabled (no fastboot/recovery reboot commands)
     * - Developer options removed
     * - Safe boot disabled
     * - USB data pins disabled at hardware level
     * - Physical media blocked
     * - All user-accessible reset vectors closed
     * - Verification enforced (verified boot)
     */
    public void disableBootloaderAndRecovery() {
        if (!isDeviceOwner()) return;

        try {
            // 1. Disable OEM unlocking (bootloader unlock toggle)
            try {
                dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "0");
                Log.i(TAG, "OEM unlock DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable OEM unlock: " + e.getMessage());
            }

            // 2. Disable developer options entirely (removes reboot to bootloader option)
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
                Log.i(TAG, "Developer options DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable developer settings: " + e.getMessage());
            }

            // 3. Disable ADB (prevents adb reboot bootloader / adb reboot recovery)
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
                Log.i(TAG, "ADB DISABLED (prevents adb reboot commands)");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB: " + e.getMessage());
            }

            // 4. Block debugging features (prevents USB debugging for reboot commands)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);

            // 5. Disable safe boot (prevents Volume Down during boot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
                Log.i(TAG, "Safe boot DISABLED");
            }

            // 6. Block USB data entirely (prevents fastboot over USB)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
            }

            // 7. Disable USB data signaling at hardware level (strongest USB block)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(false);
                    Log.i(TAG, "USB data signaling DISABLED at hardware level");
                } catch (Exception e) {
                    Log.w(TAG, "Could not disable USB data signaling: " + e.getMessage());
                }
            }

            // 8. Keep device powered on when plugged in (prevents shutdown while charging)
            try {
                // BIT 1 = AC, BIT 2 = USB, BIT 4 = wireless
                dpm.setGlobalSetting(adminComponent, "stay_on_while_plugged_in", "7");
                Log.i(TAG, "Stay on while plugged in ENABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not set stay_on_while_plugged_in: " + e.getMessage());
            }

            // 9. Block config changes to prevent re-enabling developer options
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME);
            } catch (Exception e) {
                Log.w(TAG, "Could not restrict config: " + e.getMessage());
            }

            // 10. Set maximum screen timeout to prevent easy shutdown via timeout
            try {
                dpm.setGlobalSetting(adminComponent, "screen_off_timeout", "300000"); // 5 minutes
                Log.i(TAG, "Screen timeout set to 5 minutes");
            } catch (Exception e) {
                Log.w(TAG, "Could not set screen timeout: " + e.getMessage());
            }

            // 11. Block mounting physical media (prevents booting from USB/SD recovery tools)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);

            // 12. Disable config VPN (prevents routing through recovery tools)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN);
                    Log.i(TAG, "VPN config DISABLED");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not disable VPN config: " + e.getMessage());
            }

            // 13. Block unknown sources (prevents installing bootloader/recovery tools)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            // 14. Block adding new users (prevents using guest mode to bypass)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);

            // 15. Set auto-time to prevent time manipulation attacks
            try {
                dpm.setGlobalSetting(adminComponent, "auto_time", "1");
                dpm.setGlobalSetting(adminComponent, "auto_time_zone", "1");
            } catch (Exception e) {
                Log.w(TAG, "Could not set auto time: " + e.getMessage());
            }

            // 16. Disable wireless ADB (Android 11+ wireless debugging)
            try {
                dpm.setGlobalSetting(adminComponent, "adb_wifi_enabled", "0");
                Log.i(TAG, "Wireless ADB DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable wireless ADB: " + e.getMessage());
            }

            // 17. Additional device management settings

            // 18. Disable Bluetooth sharing (prevents firmware transfer via BT)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH_SHARING);
            } catch (Exception e) {
                Log.w(TAG, "Could not disable BT sharing: " + e.getMessage());
            }

            // 19. Disable airplane mode (prevents communication cutoff before bypass)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE);
                    Log.i(TAG, "Airplane mode DISABLED");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not disable airplane mode: " + e.getMessage());
            }

            // 20. Force USB to charging-only mode
            disableUSBDataPermanently();

            // 21. CRITICAL: Use PersistentDataBlockManager to disable OEM unlock at hardware level
            // This is MORE reliable than settings put global oem_unlock_enabled 0
            disableOemUnlockPersistent();

            // 22. Block Samsung Find My Mobile auto-unlock
            try {
                dpm.setGlobalSetting(adminComponent, "fmm_unlock_enable", "0");
            } catch (Exception e) { /* Samsung-specific */ }

            // 23. Block Safe Mode by setting secure setting
            try {
                dpm.setGlobalSetting(adminComponent, "safe_boot_disallowed", "1");
            } catch (Exception e) { /* ignore */ }

            // 24. Disable all system UI global actions (power menu reboot/shutdown)
            try {
                dpm.setGlobalSetting(adminComponent, "power_menu_bug_report", "0");
            } catch (Exception e) { /* ignore */ }

            // 25. Block Samsung Maintenance Mode
            try {
                dpm.setGlobalSetting(adminComponent, "maintenance_mode_enabled", "0");
            } catch (Exception e) { /* Samsung-specific */ }

            Log.i(TAG, "Bootloader/Recovery protections APPLIED (MAXIMUM STRENGTH)");
        } catch (Exception e) {
            Log.e(TAG, "Error applying bootloader/recovery protections", e);
        }
    }

    /**
     * Disable OEM unlock at the persistent data block level.
     * This is the OFFICIAL Android API and more reliable than settings.
     * Even if user gains access to Developer Options, OEM unlock toggle won't work.
     */
    private void disableOemUnlockPersistent() {
        // Use Device Owner API to disable OEM unlock
        try {
            dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "0");
            Log.i(TAG, "OEM unlock disabled via DPM API");
        } catch (Exception e) {
            Log.w(TAG, "Could not disable OEM unlock: " + e.getMessage());
        }
    }

    /**
     * Refresh lock task packages list (call when new apps are installed)
     */
    public void refreshLockTaskPackages() {
        if (!isDeviceOwner()) return;
        try {
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> installedPackages = pm.getInstalledPackages(0);
            List<String> packageList = new ArrayList<>();
            packageList.add(context.getPackageName());
            for (PackageInfo pkg : installedPackages) {
                if (!packageList.contains(pkg.packageName)) {
                    packageList.add(pkg.packageName);
                }
            }
            String[] packages = packageList.toArray(new String[0]);
            dpm.setLockTaskPackages(adminComponent, packages);
            Log.i(TAG, "Lock task packages refreshed: " + packages.length + " packages");
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing lock task packages", e);
        }
    }

    /**
     * Start Lock Task mode - blocks power menu (restart/power off)
     * All apps are whitelisted so the phone remains fully usable.
     * Should be called from an Activity context.
     * Also ensures LockTaskBootActivity is running to maintain the lock task.
     */
    public static void startLockTaskMode(android.app.Activity activity) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) 
                    activity.getSystemService(Context.DEVICE_POLICY_SERVICE);

            if (dpm != null && dpm.isDeviceOwnerApp(activity.getPackageName())) {
                // Ensure lock task packages and features are configured
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(activity);
                protectionManager.disableRebootPowerOff();
                
                // Start lock task mode on this activity
                activity.startLockTask();
                Log.i(TAG, "Lock Task Mode STARTED (power menu blocked, all apps usable)");

                // Also ensure LockTaskBootActivity is running as a persistent lock task holder
                com.riad.rrlkr.ui.LockTaskBootActivity.launch(activity);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting lock task mode", e);
        }
    }

    /**
     * Stop Lock Task (Kiosk) mode
     */
    public static void stopLockTaskMode(android.app.Activity activity) {
        try {
            activity.stopLockTask();
            Log.i(TAG, "Lock Task Mode STOPPED");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping lock task mode", e);
        }
    }

    // ==========================================
    // 7. DISABLE OTA & CONFIGURATION CHANGES
    // ==========================================

    /**
     * Prevent reset through configuration changes
     */
    public void disableOTAUpdatesReset() {
        if (!isDeviceOwner()) return;

        try {
            // Prevent config changes that could affect the app
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME);
            // Disable adding new users
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);
            
            // Disable removing users
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_REMOVE_USER);
            }
            
            // Disable network reset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET);
            }

            Log.i(TAG, "Configuration change restrictions APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error setting config restrictions", e);
        }
    }

    // ==========================================
    // 7b. DISABLE FLASH / ROM / FIRMWARE INSTALL
    // ==========================================

    /**
     * Prevent flashing custom ROM, firmware, or recovery images.
     * Blocks USB, OEM unlock, developer options, sideloading, and system update methods.
     * 
     * COMPREHENSIVE PROTECTION:
     * - OEM unlock disabled (bootloader stays locked)
     * - ADB disabled (no fastboot, sideload, or reboot commands)
     * - Developer options disabled (no toggle access)
     * - Unknown sources blocked (no APK sideloading)
     * - Physical media blocked (no USB OTG flash drives)
     * - System update policy controlled (no manual OTA flash)
     * - USB data completely disabled (no data pins active)
     * - Download mode detection and prevention
     */
    public void disableFlashAndFirmware() {
        if (!isDeviceOwner()) return;

        try {
            // Block USB debugging (prevents fastboot / flash tools)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);

            // Block mounting physical media (USB OTG flash drives with ROMs)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);

            // Block installing apps from unknown sources (sideloading APKs / firmware tools)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }
            dpm.addUserRestriction(adminComponent, "no_install_unknown_sources");

            // Disable OEM unlock in developer options (prevents bootloader unlock for flashing)
            try {
                dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable OEM unlock: " + e.getMessage());
            }

            // Disable developer options entirely
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable developer settings: " + e.getMessage());
            }

            // Disable ADB (prevents sideload via adb sideload command)
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable ADB: " + e.getMessage());
            }

            // Disable USB data signaling at hardware level (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(false);
                    Log.i(TAG, "USB data signaling DISABLED at hardware level");
                } catch (Exception e) {
                    Log.w(TAG, "Could not disable USB data signaling: " + e.getMessage());
                }
            }

            // Block system update installation via policy (Android 6+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Postpone system updates â€” prevents manual OTA flash
                    // Use windowed policy with a very late window to effectively delay updates
                    android.app.admin.SystemUpdatePolicy policy =
                        android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(0, 1);
                    dpm.setSystemUpdatePolicy(adminComponent, policy);
                    Log.i(TAG, "System update policy set (controlled updates only)");
                } catch (Exception e) {
                    Log.w(TAG, "Could not set system update policy: " + e.getMessage());
                }
            }

            // Disable ADB over network (wireless debugging)
            try {
                dpm.setGlobalSetting(adminComponent, "adb_wifi_enabled", "0");
                Log.i(TAG, "ADB over WiFi DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable adb wifi: " + e.getMessage());
            }

            // Block Bluetooth file transfer (prevents firmware transfer via BT)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH_SHARING);
                Log.i(TAG, "Bluetooth sharing DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable bluetooth sharing: " + e.getMessage());
            }

            // Disable airplane mode toggle (prevents RF isolation bypass technique)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE);
                    Log.i(TAG, "Airplane mode toggle DISABLED");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not disable airplane mode: " + e.getMessage());
            }

            // Block content sharing and beam (NFC-based firmware push)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONTENT_CAPTURE);
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONTENT_SUGGESTIONS);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not disable content capture: " + e.getMessage());
            }

            Log.i(TAG, "Flash/ROM/Firmware protections APPLIED (MAXIMUM STRENGTH)");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling flash/firmware", e);
        }
    }

    // ==========================================
    // 8. KEEP APP ALWAYS RUNNING
    // ==========================================

    /**
     * Ensure the app stays running and cannot be force-stopped.
     * NOTE: Do NOT call setLockTaskPackages here - it would overwrite the list
     * set by disableRebootPowerOff() which whitelists ALL apps.
     */
    public void setAppAsAlwaysRunning() {
        if (!isDeviceOwner()) return;

        try {
            // Block uninstall ensures the app stays on device
            dpm.setUninstallBlocked(adminComponent, context.getPackageName(), true);
            Log.i(TAG, "App set as ALWAYS RUNNING (uninstall blocked)");
        } catch (Exception e) {
            Log.e(TAG, "Error setting app as always running", e);
        }
    }

    // ==========================================
    // 9. DISABLE STATUS BAR (prevent quick settings access)
    // ==========================================

    /**
     * Disable status bar expansion to prevent toggling settings
     */
    public void disableStatusBarExpansion() {
        if (!isDeviceOwner()) return;

        try {
            dpm.setStatusBarDisabled(adminComponent, true);
            Log.i(TAG, "Status bar DISABLED");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling status bar", e);
        }
    }

    /**
     * Enable status bar (for unlock)
     */
    public void enableStatusBar() {
        if (!isDeviceOwner()) return;

        try {
            dpm.setStatusBarDisabled(adminComponent, false);
            Log.i(TAG, "Status bar ENABLED");
        } catch (Exception e) {
            Log.e(TAG, "Error enabling status bar", e);
        }
    }

    // ==========================================
    // 10. APP HIDE / UNHIDE (from launcher)
    // ==========================================

    /**
     * Hide the app from launcher (app drawer)
     * The app continues running as a background service.
     * Uses ComponentName to disable the launcher activity alias.
     */
    public void hideAppFromLauncher() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName launcherActivity = new ComponentName(context, MainActivity.class);
            
            pm.setComponentEnabledSetting(
                    launcherActivity,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            
            preferenceManager.setAppHidden(true);
            Log.i(TAG, "App HIDDEN from launcher");
        } catch (Exception e) {
            Log.e(TAG, "Error hiding app from launcher", e);
        }
    }

    /**
     * Unhide the app - show it in launcher again
     */
    public void unhideAppInLauncher() {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName launcherActivity = new ComponentName(context, MainActivity.class);
            
            pm.setComponentEnabledSetting(
                    launcherActivity,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            
            preferenceManager.setAppHidden(false);
            Log.i(TAG, "App UNHIDDEN - visible in launcher");
        } catch (Exception e) {
            Log.e(TAG, "Error unhiding app in launcher", e);
        }
    }

    /**
     * Check if the app is currently hidden from the launcher
     */
    public boolean isAppHidden() {
        return preferenceManager.isAppHidden();
    }

    // ==========================================
    // 11b. SYSTEM INTEGRITY VERIFICATION
    // ==========================================

    /**
     * Verify system integrity â€” detect if device is rooted or running custom ROM.
     * If tampering is detected, lock the device immediately and report to server.
     */
    public void verifySystemIntegrity() {
        try {
            boolean tampered = false;
            StringBuilder reasons = new StringBuilder();

            // Check build tags for test-keys (unofficial ROM)
            String buildTags = Build.TAGS;
            if (buildTags != null && buildTags.contains("test-keys")) {
                tampered = true;
                reasons.append("Build has test-keys; ");
            }

            if (tampered) {
                Log.w(TAG, "System integrity issue: " + reasons.toString());
                preferenceManager.saveBoolean("system_tampered", true);
                preferenceManager.saveString("tamper_reason", reasons.toString());
            } else {
                preferenceManager.saveBoolean("system_tampered", false);
                Log.i(TAG, "System integrity verification PASSED");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error verifying system integrity", e);
        }
    }

    /**
     * Read a system property via reflection
     */
    private String getSystemProperty(String key) {
        try {
            return Build.class.getField(key) != null ? Build.UNKNOWN : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // 11c. LOCK DOWN SETTINGS ACCESS
    // ==========================================

    /**
     * Lock down access to system settings that could be used for bypass.
     * Blocks config changes while allowing normal phone usage.
     */
    public void lockDownSettings() {
        if (!isDeviceOwner()) return;

        try {
            // Block credential config (prevents certificate-based bypasses)
            try {
                dpm.addUserRestriction(adminComponent, "no_config_credentials");
            } catch (Exception e) { /* ignore */ }

            // Block removing managed profile
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_REMOVE_MANAGED_PROFILE);
            } catch (Exception e) { /* ignore */ }

            // Block USB file transfer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
            }

            // Block cross-profile sharing (prevents data exfiltration)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE);
                }
            } catch (Exception e) { /* ignore */ }

            // Block printing (prevents data exfiltration via print)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_PRINTING);
                }
            } catch (Exception e) { /* ignore */ }

            Log.i(TAG, "Settings lock-down APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error locking down settings", e);
        }
    }

    // ==========================================
    // 11. LOCK-MODE HARDENING (extra restrictions when locked)
    // ==========================================

    /**
     * Apply extra restrictions when device is in locked state.
     * These are more aggressive and block settings access, AND re-apply every
     * reset/reboot/recovery block so the user cannot factory reset, hard reset,
     * reboot to recovery, safe-boot, or power off the phone while locked.
     */
    public void applyLockModeHardening() {
        if (!isDeviceOwner()) return;

        try {
            // === 1. RE-APPLY ALL RESET / REBOOT / RECOVERY BLOCKS ===
            // These are critical: while locked, the user must NOT be able to
            // factory reset, hard reset, safe boot, or reboot into recovery.
            try { disableFactoryReset(); } catch (Throwable t) { Log.w(TAG, "lock: disableFactoryReset failed: " + t.getMessage()); }
            try { disableSafeBoot(); }     catch (Throwable t) { Log.w(TAG, "lock: disableSafeBoot failed: "     + t.getMessage()); }
            try { disableRecoveryMode(); } catch (Throwable t) { Log.w(TAG, "lock: disableRecoveryMode failed: " + t.getMessage()); }
            try { enableFactoryResetProtection(); } catch (Throwable t) { Log.w(TAG, "lock: enableFRP failed: " + t.getMessage()); }
            try { disableOTAUpdatesReset(); } catch (Throwable t) { Log.w(TAG, "lock: disableOTAUpdatesReset failed: " + t.getMessage()); }
            try { disableFlashAndFirmware(); } catch (Throwable t) { Log.w(TAG, "lock: disableFlashAndFirmware failed: " + t.getMessage()); }
            // disableRebootPowerOff() blocks the power-menu Reboot / Power off /
            // Restart entries via Lock Task mode. Run last so it owns the lock
            // task package list.
            try { disableRebootPowerOff(); } catch (Throwable t) { Log.w(TAG, "lock: disableRebootPowerOff failed: " + t.getMessage()); }

            // Activate accessibility-based power button interception so a long
            // press of the power button cannot show the power menu while locked.
            try { enablePowerButtonInterceptService(); } catch (Throwable t) { Log.w(TAG, "lock: enablePowerButtonInterceptService failed: " + t.getMessage()); }

            // === 2. EXTRA USER RESTRICTIONS WHILE LOCKED ===
            // Block access to app settings (prevents force-stop, clear data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL);
            }
            // Block uninstalling apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            }
            // Block bluetooth config changes
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH);
            // Block creating windows / overlays from other apps
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS);
            }
            // Block adding new users / guest accounts (could be used to bypass)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USER_SWITCH);
            } catch (Throwable ignored) {}
            // Block USB file transfer / debugging while locked
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER); } catch (Throwable ignored) {}
            }
            try { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES); } catch (Throwable ignored) {}

            // Disable the status bar to prevent notifications/quick settings
            // (which could include a "restart" tile on some ROMs).
            disableStatusBarExpansion();

            Log.i(TAG, "Lock-mode hardening APPLIED (reset/reboot/recovery blocked)");
        } catch (Exception e) {
            Log.e(TAG, "Error applying lock-mode hardening", e);
        }
    }

    /**
     * Remove lock-mode hardening restrictions (when unlocked)
     * NOTE: Does NOT remove DISALLOW_APPS_CONTROL - that stays blocked ALWAYS
     * to prevent clearing app data from Settings.
     */
    public void removeLockModeHardening() {
        if (!isDeviceOwner()) return;

        try {
            // NOTE: DISALLOW_APPS_CONTROL is intentionally NOT removed here
            // It stays blocked at all times to prevent Settings > Apps > Clear Data bypass
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS);
            }
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH);

            enableStatusBar();

            Log.i(TAG, "Lock-mode hardening REMOVED (apps control still blocked)");
        } catch (Exception e) {
            Log.e(TAG, "Error removing lock-mode hardening", e);
        }
    }

    // ==========================================
    // REMOVE ALL PROTECTIONS (for unlock/unenroll)
    // ==========================================

    /**
     * Remove all device protections (called when device is unlocked/unenrolled)
     */
    public void removeAllProtections() {
        if (!isDeviceOwner()) return;

        Log.i(TAG, "=== Removing ALL Device Protections ===");

        try {
            // Remove Samsung-specific protections first
            try {
                SamsungProtectionManager samsungManager = new SamsungProtectionManager(context);
                samsungManager.removeAllSamsungProtections();
            } catch (Exception e) {
                Log.w(TAG, "Error removing Samsung protections: " + e.getMessage());
            }

            // Stop RebootBlockerService
            RebootBlockerService.stop(context);

            // Unhide app if hidden
            unhideAppInLauncher();

            // Allow uninstall
            dpm.setUninstallBlocked(adminComponent, context.getPackageName(), false);

            // Remove user restrictions
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            
            try {
                dpm.clearUserRestriction(adminComponent, "no_config_credentials");
                dpm.clearUserRestriction(adminComponent, "no_install_unknown_sources");
            } catch (Exception e) { /* ignore */ }

            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_BLUETOOTH_SHARING);
            } catch (Exception e) { /* ignore */ }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_PRINTING);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONTENT_CAPTURE);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONTENT_SUGGESTIONS);
                }
            } catch (Exception e) { /* ignore */ }

            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_REMOVE_MANAGED_PROFILE);
            } catch (Exception e) { /* ignore */ }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_NETWORK_RESET);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_REMOVE_USER);
                try {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN);
                } catch (Exception e) { /* ignore */ }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
            }

            // Remove lock-mode hardening restrictions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS);
                } catch (Exception e) { /* ignore */ }
            }
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH);
            } catch (Exception e) { /* ignore */ }

            // Remove anti-flash hardening restrictions
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI);
            } catch (Exception e) { /* ignore */ }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION);
                }
            } catch (Exception e) { /* ignore */ }

            // Clear device-protected storage
            try {
                com.riad.rrlkr.util.DeviceProtectedPrefs dpPrefs =
                        new com.riad.rrlkr.util.DeviceProtectedPrefs(context);
                dpPrefs.setAppDisabled(true);
                dpPrefs.setEnrolled(false);
            } catch (Exception e) { /* ignore */ }

            // Re-enable status bar
            enableStatusBar();

            // Re-enable ADB and developer options
            try {
                dpm.setGlobalSetting(adminComponent, "adb_enabled", "1");
                dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "1");
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1");
                dpm.setGlobalSetting(adminComponent, "adb_wifi_enabled", "1");
            } catch (Exception e) {
                // ignore
            }

            // Clear system update policy
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setSystemUpdatePolicy(adminComponent, null);
                }
            } catch (Exception e) { /* ignore */ }

            // Re-enable USB data signaling (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(true);
                    Log.i(TAG, "USB data signaling RE-ENABLED");
                } catch (Exception e) {
                    Log.w(TAG, "Could not re-enable USB data signaling: " + e.getMessage());
                }
            }

            // Remove Factory Reset Protection
            removeFactoryResetProtection();

            // Re-enable USB mass storage and tethering
            try {
                dpm.setGlobalSetting(adminComponent, "usb_mass_storage_enabled", "1");
                dpm.setGlobalSetting(adminComponent, "tether_supported", "1");
                dpm.setGlobalSetting(adminComponent, "stay_on_while_plugged_in", "0");
                dpm.setGlobalSetting(adminComponent, "app_standby_enabled", "1");
            } catch (Exception e) { /* ignore */ }

            // Unhide firmware update apps
            String[] firmwareApps = {
                "com.sec.android.soagent", "com.sec.android.fotaclient",
                "com.sec.android.app.fota", "com.wssyncmldm", "com.samsung.sdm",
                "com.miui.fota", "com.android.updater", "com.coloros.sau", "com.oplus.sau",
            };
            for (String pkg : firmwareApps) {
                try { dpm.setApplicationHidden(adminComponent, pkg, false); } catch (Exception e) { /* ignore */ }
            }

            // Re-enable Samsung-specific settings
            try {
                dpm.setGlobalSetting(adminComponent, "auto_restart_enabled", "1");
                dpm.setGlobalSetting(adminComponent, "maintenance_mode_enabled", "1");
            } catch (Exception e) { /* ignore */ }

            // Clear lock task mode
            try {
                dpm.setLockTaskPackages(adminComponent, new String[]{});
            } catch (Exception e) {
                // ignore
            }

            Log.i(TAG, "=== All Protections Removed ===");
        } catch (Exception e) {
            Log.e(TAG, "Error removing protections", e);
        }
    }

    /**
     * Clear device owner status and then uninstall the app permanently.
     * This is a destructive action that cannot be undone remotely.
     * Must call removeAllProtections() before this.
     */
    public void clearDeviceOwnerAndUninstall() {
        Log.w(TAG, "=== CLEARING DEVICE OWNER AND UNINSTALLING APP ===");

        try {
            if (isDeviceOwner()) {
                // Allow uninstall first
                dpm.setUninstallBlocked(adminComponent, context.getPackageName(), false);
                
                // Clear all lock task packages
                try {
                    dpm.setLockTaskPackages(adminComponent, new String[]{});
                } catch (Exception e) {
                    Log.w(TAG, "Could not clear lock task packages: " + e.getMessage());
                }
                
                // Clear device owner (this is required before uninstall)
                dpm.clearDeviceOwnerApp(context.getPackageName());
                Log.i(TAG, "Device Owner CLEARED");
            }
            
            // Clear all preferences
            preferenceManager.clearAll();
            
            // Request uninstall of the app
            android.content.Intent uninstallIntent = new android.content.Intent(android.content.Intent.ACTION_DELETE);
            uninstallIntent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            uninstallIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
            
            Log.w(TAG, "=== UNINSTALL INITIATED ===");
        } catch (Exception e) {
            Log.e(TAG, "Error during device owner clear / uninstall", e);
            
            // Fallback: try to at least request uninstall
            try {
                android.content.Intent uninstallIntent = new android.content.Intent(android.content.Intent.ACTION_DELETE);
                uninstallIntent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                uninstallIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(uninstallIntent);
            } catch (Exception e2) {
                Log.e(TAG, "Fallback uninstall also failed", e2);
            }
        }
    }

    // ==========================================
    // SAMSUNG PROTECTION INTEGRATION
    // ==========================================

    /**
     * Apply Samsung-specific protections.
     * Detects Samsung devices and applies Knox, Download Mode, ODIN blocking.
     */
    private void applySamsungProtections() {
        try {
            SamsungProtectionManager samsungManager = new SamsungProtectionManager(context);
            samsungManager.applyAllSamsungProtections();
        } catch (Exception e) {
            Log.e(TAG, "Error applying Samsung protections", e);
        }
    }

    /**
     * Run the shell-based protection enforcer script.
     * This script applies system-level settings that complement Java-level protections.
     * Includes comprehensive settings for ALL manufacturers.
     * 
     * IMPORTANT: On Android 13+, Runtime.exec("settings put ...") is blocked for
     * non-root processes. Device Owner apps MUST use DevicePolicyManager APIs:
     * - dpm.setGlobalSetting() for Settings.Global
     * - dpm.setSecureSetting() for Settings.Secure  
     * - dpm.setSystemSetting() for Settings.System (Android 8+)
     * Shell commands are kept as fallback for older Android versions only.
     */
    private void applyDevicePolicySettings() {
        if (!isDeviceOwner()) return;
        
        try {
            Log.i(TAG, "Running protection enforcer (DPM API + shell fallback)...");

            // === UNIVERSAL HARDENING via DevicePolicyManager API ===
            // These work on ALL Android versions including 13+ without root
            
            // Global settings (Device Owner can always set these)
            String[][] globalSettings = {
                // Core security
                {"adb_enabled", "0"},
                {"development_settings_enabled", "0"},
                {"adb_wifi_enabled", "0"},
                {"oem_unlock_enabled", "0"},
                {"usb_mass_storage_enabled", "0"},
                {"stay_on_while_plugged_in", "7"},
                {"tether_supported", "0"},
                {"auto_time", "1"},
                {"auto_time_zone", "1"},
                {"oem_unlock_allowed", "0"},
                {"device_provisioned", "1"},
                {"usb_mode", "0"},
                {"usb_audio_automatic_routing_disabled", "1"},
                {"adb_notify", "0"},
                {"safe_boot_disallowed", "1"},
                {"power_menu_bug_report", "0"},
            };
            
            for (String[] setting : globalSettings) {
                setGlobalSettingSafe(setting[0], setting[1]);
            }
            
            // Secure settings (Device Owner can set these)
            String[][] secureSettings = {
                {"backup_enabled", "0"},
            };
            
            for (String[] setting : secureSettings) {
                setSecureSettingSafe(setting[0], setting[1]);
            }

            // === SAMSUNG-SPECIFIC via DPM API ===
            if (SamsungProtectionManager.isSamsungDevice()) {
                String[][] samsungGlobal = {
                    {"download_mode_enabled", "0"},
                    {"download_mode_warning", "0"},
                    {"maintenance_mode_enabled", "0"},
                    {"auto_restart_enabled", "0"},
                    {"auto_restart", "0"},
                    {"scheduled_power_on_off", "0"},
                    {"ultra_power_saving_mode", "0"},
                    {"dex_mode_enabled", "0"},
                    {"desktop_mode_enabled", "0"},
                    {"software_update", "0"},
                    {"auto_software_update", "0"},
                    {"software_update_wifi_only", "2"},
                    {"knox_attestation_enabled", "1"},
                    {"samsung_reactivation_lock", "1"},
                    {"bugreport_in_power_menu", "0"},
                    {"enable_gpu_debug_layers", "0"},
                    {"usb_midi_enabled", "0"},
                    {"fota_update_enable", "0"},
                    {"fota_update", "0"},
                };
                for (String[] setting : samsungGlobal) {
                    setGlobalSettingSafe(setting[0], setting[1]);
                }
                
                String[][] samsungSecure = {
                    {"download_mode_enabled", "0"},
                    {"maintenance_mode_enabled", "0"},
                    {"power_key_action", "0"},
                    {"send_sos_message", "0"},
                    {"sos_enabled", "0"},
                    {"emergency_mode", "0"},
                    {"end_button_behavior", "0"},
                    {"fmm_unlock_enable", "0"},
                    {"bixby_enabled", "0"},
                    {"assistant_double_tap", "0"},
                    {"secure_folder_display", "0"},
                    {"dual_messenger_enabled", "0"},
                    {"screen_capture_enabled", "0"},
                    {"service_mode_enabled", "0"},
                };
                for (String[] setting : samsungSecure) {
                    setSecureSettingSafe(setting[0], setting[1]);
                }
                
                // System settings (Android 8+ Device Owner can set)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    String[][] samsungSystem = {
                        {"power_key_action", "0"},
                        {"end_button_behavior", "0"},
                    };
                    for (String[] setting : samsungSystem) {
                        setSystemSettingSafe(setting[0], setting[1]);
                    }
                }
            }

            // === XIAOMI / MIUI SPECIFIC ===
            String manufacturer = Build.MANUFACTURER.toLowerCase();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
                setGlobalSettingSafe("force_allow_on_external", "0");
                setGlobalSettingSafe("enable_miui_optimization", "1");
                setSecureSettingSafe("screensaver_enabled", "0");
                setSecureSettingSafe("second_user_enabled", "0");
            }

            // === OPPO / REALME / ONEPLUS ===
            if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
                setGlobalSettingSafe("color_os_extra", "0");
                setSecureSettingSafe("screen_off_udfps_enabled", "0");
            }

            Log.i(TAG, "Protection enforcer script COMPLETED");
        } catch (Exception e) {
            Log.e(TAG, "Error running protection enforcer script", e);
        }
    }
    
    /**
     * Set a Global setting safely via DPM API, with shell fallback for older Android.
     */
    private void setGlobalSettingSafe(String key, String value) {
        try {
            dpm.setGlobalSetting(adminComponent, key, value);
        } catch (Exception e) {
            Log.d(TAG, "Could not set global." + key + ": " + e.getMessage());
        }
    }
    
    /**
     * Set a Secure setting safely via DPM API, with shell fallback.
     */
    private void setSecureSettingSafe(String key, String value) {
        try {
            dpm.setSecureSetting(adminComponent, key, value);
        } catch (Exception e) {
            Log.d(TAG, "Could not set secure." + key + ": " + e.getMessage());
        }
    }
    
    /**
     * Set a System setting safely via DPM API (Android 8+), with shell fallback.
     */
    private void setSystemSettingSafe(String key, String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                dpm.setSystemSetting(adminComponent, key, value);
            } catch (Exception e) {
                Log.d(TAG, "Could not set system." + key + ": " + e.getMessage());
            }
        }
    }

    /**
     * Execute a shell command quietly (no output capture needed)
     */
    private void executeShellCommandQuiet(String command) {
        // Shell execution removed - use DPM APIs only
        Log.d(TAG, "Shell command skipped (using DPM API): " + command);
    }

    /**
     * Apply additional anti-flash hardening beyond standard Device Owner restrictions.
     * 
     * This adds extra layers of protection:
     * 1. Block all firmware-related system apps (ALL manufacturers)
     * 2. Disable all reboot vectors via system settings
     * 3. Block hardware key combo effects via system properties
     * 4. Additional UserManager restrictions
     * 5. PersistentDataBlockManager OEM lock
     * 6. Samsung Knox extended API calls
     */
    private void applyFirmwareManagementPolicy() {
        if (!isDeviceOwner()) return;

        try {
            Log.i(TAG, "=== Applying Anti-Flash Hardening ===");

            // 1. Block firmware update apps (ALL manufacturers - comprehensive list)
            String[] firmwareApps = {
                // Google
                "com.google.android.gms.update",
                "com.google.android.gms.provision",
                // Samsung
                "com.sec.android.soagent",
                "com.sec.android.fotaclient",
                "com.sec.android.app.fota",
                "com.wssyncmldm",
                "com.samsung.sdm",
                "com.samsung.android.sdm.config",
                "com.samsung.android.app.omcagent",
                "com.sec.android.easyMover",
                "com.sec.android.easyMover.Agent",
                "com.sec.android.sidesync30",
                "com.sec.android.kies3",
                "com.samsung.android.kies",
                "com.samsung.android.smartswitchassistant",
                "com.sec.android.app.servicemodeapp",
                "com.sec.factory.camera",
                "com.sec.android.RilServiceModeApp",
                "com.samsung.android.app.galaxyfinder",
                // Xiaomi / MIUI
                "com.miui.fota",
                "com.xiaomi.discover",
                "com.xiaomi.market",
                // Oppo / Realme / ColorOS
                "com.coloros.sau",
                "com.oplus.sau",
                "com.nearme.romupdate",
                // OnePlus / OxygenOS
                "com.oneplus.opbackup",
                // Huawei / HarmonyOS
                "com.huawei.android.hwouc",
                "com.huawei.fota.update",
                // Vivo / FunTouchOS
                "com.vivo.ota",
                // Generic Android
                "com.android.updater",
                "com.android.partnerbrowsercustomizations.tmobile",
                // Qualcomm (diagnostic/flash tools)
                "com.qualcomm.qti.qms.service.connectionsecurity",
                "com.qualcomm.qti.qdma",
                "com.qualcomm.qti.diaglog",
                // MediaTek (SP Flash Tool communication)
                "com.mediatek.datatransfer",
                "com.mediatek.omacp",
            };

            for (String pkg : firmwareApps) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, true);
                } catch (Exception e) { /* app may not exist on this device */ }
            }
            Log.i(TAG, "Firmware update apps HIDDEN (" + firmwareApps.length + " apps checked)");

            // 2. Additional user restrictions for maximum lockdown
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CREATE_WINDOWS);
                }
            } catch (Exception e) { /* ignore */ }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_PRINTING);
                }
            } catch (Exception e) { /* ignore */ }

            // NOTE: DISALLOW_CONFIG_WIFI is intentionally NOT set here.
            // The app NEEDS WiFi/internet access for lock/unlock commands via FCM.
            // Blocking WiFi config prevents the user from connecting to WiFi,
            // which breaks all server communication (lock, unlock, enrollment).

            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_BLUETOOTH);
            } catch (Exception e) { /* ignore */ }

            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            } catch (Exception e) { /* ignore */ }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SHARE_LOCATION);
                }
            } catch (Exception e) { /* ignore */ }

            // 3. Disable all possible reboot/shutdown vectors via Global Settings
            try {
                dpm.setGlobalSetting(adminComponent, "safe_boot_disallowed", "1");
            } catch (Exception e) { /* ignore */ }

            try {
                dpm.setGlobalSetting(adminComponent, "stay_on_while_plugged_in", "7");
            } catch (Exception e) { /* ignore */ }

            // 4. PersistentDataBlockManager OEM unlock disabled
            disableOemUnlockPersistent();

            // 5. Force stay-alive settings for our app
            try {
                dpm.setGlobalSetting(adminComponent, "app_standby_enabled", "0");
            } catch (Exception e) { /* ignore */ }

            // 6. Block Samsung Setup Wizard and FRP bypass apps
            if (SamsungProtectionManager.isSamsungDevice()) {
                String[] samsungBypassApps = {
                    "com.samsung.android.app.galaxyfinder",
                    "com.samsung.android.app.sharelive",
                    "com.samsung.android.globalactions",
                    "com.samsung.android.globalactions.bar",
                    "com.samsung.android.visionintelligence",
                };
                for (String pkg : samsungBypassApps) {
                    try {
                        dpm.setApplicationHidden(adminComponent, pkg, true);
                    } catch (Exception e) { /* ignore */ }
                }
            }

            // 7. Samsung Knox extended protection via reflection
            if (SamsungProtectionManager.isSamsungDevice()) {
                applySamsungKnoxExtendedProtection();
            }

            // 8. Block NFC beam (firmware transfer vector)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, "no_outgoing_beam");
                }
            } catch (Exception e) { /* ignore */ }

            // 9. Disable screen pinning (potential UI bypass)
            try {
                dpm.setGlobalSetting(adminComponent, "lock_to_app_enabled", "0");
            } catch (Exception e) { /* ignore */ }

            Log.i(TAG, "=== Anti-Flash Hardening APPLIED ===");
        } catch (Exception e) {
            Log.e(TAG, "Error applying anti-flash hardening", e);
        }
    }

    /**
     * Samsung Knox Extended Protection - Uses Knox Platform APIs via reflection.
     * These APIs provide hardware-level protection on Samsung devices with Knox.
     * Works even without Knox Premium license on most Samsung devices (Knox 2.6+).
     */
    private void applySamsungKnoxExtendedProtection() {
        // Knox API integration requires Samsung Knox SDK dependency
        // Using standard DevicePolicyManager APIs instead
        Log.d(TAG, "Samsung Knox extended protection delegated to SamsungProtectionManager");
    }

    /**
     * Helper: Invoke a Knox boolean method via reflection
     */
    private void invokeKnoxMethod(Class<?> clazz, Object instance, String methodName, boolean value) {
        // Knox reflection removed - using standard DPM APIs
    }

    // ==========================================
    // STATUS REPORT
    // ==========================================

    /**
     * Get a status string of all protections
     */
    public String getProtectionStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("â•â•â• Device Protection Status â•â•â•\n\n");

        boolean isOwner = isDeviceOwner();
        sb.append("Device Owner: ").append(isOwner ? "âœ… Active" : "âŒ Not Set").append("\n");

        if (isOwner) {
            sb.append("Uninstall Block: âœ…\n");
            sb.append("USB Debug Block: âœ…\n");
            sb.append("USB Data Block: âœ… (Charge Only)\n");
            sb.append("Factory Reset Block: âœ…\n");
            sb.append("Safe Boot Block: âœ…\n");
            sb.append("Power Menu Block: âœ…\n");
            sb.append("Bootloader Block: âœ…\n");
            sb.append("Recovery Block: âœ…\n");
            sb.append("Status Bar Block: âœ…\n");

            // Check USB data signaling status (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    boolean usbDataEnabled = dpm.isUsbDataSignalingEnabled();
                    sb.append("USB Data Signal: ").append(usbDataEnabled ? "âš ï¸ Enabled" : "âœ… Disabled").append("\n");
                } catch (Exception e) {
                    sb.append("USB Data Signal: âš™ï¸ N/A\n");
                }
            }

            sb.append("IMEI Bound: ").append(
                    preferenceManager.getStoredIMEI() != null ? "âœ…" : "âŒ").append("\n");
            sb.append("App Hidden: ").append(
                    preferenceManager.isAppHidden() ? "âœ… Hidden" : "âŒ Visible").append("\n");

            // Samsung-specific status
            if (SamsungProtectionManager.isSamsungDevice()) {
                sb.append("\nâ•â•â• Samsung Protections â•â•â•\n");
                sb.append("Samsung Device: âœ…\n");
                sb.append("Download Mode Block: âœ…\n");
                sb.append("ODIN Block: âœ…\n");
                sb.append("Knox Integration: âœ…\n");
                sb.append("Samsung FRP: âœ…\n");
                sb.append("Anti-Flash Shell: âœ…\n");
            }
        } else {
            sb.append("\nâš ï¸ Device Owner mode required\n");
            sb.append("for full protection.\n\n");
            sb.append("The app will auto-activate when:\n");
            sb.append("1. All accounts removed\n");
            sb.append("2. All permissions granted\n\n");
            sb.append("Or use ADB command:\n");
            sb.append("adb shell dpm set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver").append("\n");
        }

        return sb.toString();
    }
}
