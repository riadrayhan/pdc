package com.riad.rrlkr.admin;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.SamsungProtectionManager;
import com.riad.rrlkr.service.ZeroTouchEnrollmentService;
import com.riad.rrlkr.util.PreferenceManager;
import com.riad.rrlkr.BuildConfig;

/**
 * Device Admin Receiver - Handles device administration events
 * This is crucial for device management functionality.
 * Also handles Device Owner provisioning completion.
 */
public class EMIDeviceAdminReceiver extends DeviceAdminReceiver {
    
    private static final String TAG = "EMIDeviceAdmin";
    
    /**
     * Get the ComponentName for this receiver
     */
    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getApplicationContext(), EMIDeviceAdminReceiver.class);
    }
    
    /**
     * Check if device admin is active
     */
    public static boolean isAdminActive(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            return dpm.isAdminActive(getComponentName(context));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if this app is Device Owner
     */
    public static boolean isDeviceOwner(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            return dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin Enabled");
        Toast.makeText(context, "RR Device Manager: Admin Enabled", Toast.LENGTH_SHORT).show();
        
        // Save admin status
        PreferenceManager prefs = new PreferenceManager(context);
        prefs.setDeviceAdminEnabled(true);
        
        // Only apply protections and start monitoring if NOT in ZTE provisioning
        // During ZTE, the ZeroTouchEnrollmentService handles everything
        boolean zteProvisioning = prefs.getBoolean("zte_provisioning_active", false);
        if (!zteProvisioning) {
            // Start monitoring service
            DeviceMonitorService.start(context);
            
            // Auto-apply protections if Device Owner â€” but DELAYED
            // applyAllProtections() disables USB data which kills ADB connection
            // Delay 30 seconds so provisioning + enrollment can complete first
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                Log.i(TAG, "Device Owner confirmed - scheduling protections in 30s");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "Applying ALL protections (delayed)");
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                    protectionManager.applyAllProtections();
                }, 30000);
            }
        } else {
            Log.i(TAG, "ZTE provisioning active â€” skipping protections & monitor service start");
        }
    }
    
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Admin Disabled");
        Toast.makeText(context, "RR Device Manager: Admin Disabled", Toast.LENGTH_SHORT).show();
        
        // Save admin status
        PreferenceManager prefs = new PreferenceManager(context);
        prefs.setDeviceAdminEnabled(false);
    }
    
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Warn user about disabling device admin
        return "Warning: Disabling device management may affect device security and compliance.";
    }

    /**
     * Called when managed provisioning completes and this app becomes Device Owner.
     * This is the entry point for auto-activation via managed provisioning.
     * 
     * ZERO TOUCH ENROLLMENT v2.0:
     * When provisioned via QR code, the intent contains admin extras bundle with:
     * - server_url: API base URL for enrollment
     * - auto_enroll: Whether to auto-enroll without user interaction
     * - auto_lock_on_enroll: Whether to lock the device after enrollment
     * - default_lock_message: Lock screen message
     * - default_contact_number: Contact number on lock screen
     * - wifi_ssid/wifi_password/wifi_security: WiFi credentials for auto-connect
     * - zte_version: ZTE protocol version (2.0)
     * 
     * Samsung Knox / One UI 5+ (Android 13+) Compatibility:
     * Samsung QR provisioning does NOT pass admin extras bundle.
     * Samsung's provisioning parser rejects nested JSON objects.
     * We detect Samsung mode and auto-enroll using BuildConfig.SERVER_URL.
     *
     * Android 14+ (API 34) Compatibility:
     * Android 14 requires FOREGROUND_SERVICE_TYPE to be declared.
     * Device Owner apps get automatic exemption from background restrictions.
     * 
     * On provisioning complete, this receiver:
     * 1. Validates Device Owner status
     * 2. Immediately applies critical restrictions (factory reset, USB, safe boot)
     * 3. Grants all runtime permissions silently
     * 4. Blocks app uninstall
     * 5. Starts ZeroTouchEnrollmentService v2.0 for full enrollment pipeline
     */
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        Log.i(TAG, "=== PROVISIONING COMPLETE - Device Owner Activated! ===");
        Log.i(TAG, "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        Log.i(TAG, "Android: " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
        
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = getComponentName(context);
        
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.e(TAG, "Provisioning complete but NOT Device Owner â€” aborting");
            return;
        }
        
        Log.i(TAG, "Device Owner confirmed via provisioning");
        
        // Detect Samsung device
        boolean isSamsung = android.os.Build.MANUFACTURER.toLowerCase().contains("samsung");
        if (isSamsung) {
            Log.i(TAG, "=== Samsung device detected â€” using Samsung QR provisioning mode ===");
        }
        
        // Save admin status immediately
        PreferenceManager prefs = new PreferenceManager(context);
        prefs.setDeviceAdminEnabled(true);
        
        // Mark that ZTE provisioning is in progress â€” device must NOT auto-lock
        prefs.saveBoolean("zte_provisioning_active", true);
        prefs.saveBoolean("admin_lock_command", false);
        prefs.setDeviceLocked(false);
        
        // ======= IMMEDIATE SECURITY HARDENING =======
        // Apply critical restrictions BEFORE anything else to prevent tampering
        applyImmediateRestrictions(context, dpm, admin);
        
        // Block uninstall of our app
        try {
            dpm.setUninstallBlocked(admin, context.getPackageName(), true);
            Log.i(TAG, "App uninstall blocked");
        } catch (Exception e) {
            Log.w(TAG, "Could not block uninstall: " + e.getMessage());
        }
        
        // Grant critical permissions silently (including Android 13+ and 14+)
        grantCriticalPermissions(context, dpm, admin);
        
        // Setup Factory Reset Protection (FRP)
        try {
            // Seed the default FRP Google account from BuildConfig if not already set.
            // This guarantees that even if the admin never sends a "set-frp-account" command,
            // the device is still locked to OUR account post-reset.
            String existingFrp = prefs.getFRPAccount();
            if (existingFrp == null || existingFrp.isEmpty()) {
                prefs.setFRPAccount(BuildConfig.DEFAULT_FRP_ACCOUNT);
                Log.i(TAG, "FRP account seeded from BuildConfig: " + BuildConfig.DEFAULT_FRP_ACCOUNT);
            }
            com.riad.rrlkr.receiver.FactoryResetProtectionReceiver.setupFactoryResetProtection(context);
            Log.i(TAG, "FRP configured");
        } catch (Exception e) {
            Log.w(TAG, "FRP setup warning: " + e.getMessage());
        }
        
        // ======= SAMSUNG KNOX INITIALIZATION =======
        if (isSamsung) {
            try {
                SamsungProtectionManager samsungManager = new SamsungProtectionManager(context);
                samsungManager.initKnoxLicense();
                Log.i(TAG, "Samsung Knox initialized during provisioning");
            } catch (Exception e) {
                Log.w(TAG, "Samsung Knox init warning: " + e.getMessage());
            }
        }
        
        // ======= ZERO TOUCH ENROLLMENT v2.0 =======
        PersistableBundle adminExtras = null;
        
        if (intent != null) {
            adminExtras = intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        }
        
        boolean autoEnroll = true; // Default to auto-enroll (Samsung QR has no admin extras)
        String serverUrl = BuildConfig.SERVER_URL; // Default server URL from build config
        
        if (adminExtras != null) {
            // auto_enroll may come as boolean or string "true" depending on QR parser
            autoEnroll = adminExtras.getBoolean("auto_enroll", true);
            if (!autoEnroll) {
                String autoEnrollStr = adminExtras.getString("auto_enroll");
                autoEnroll = "true".equalsIgnoreCase(autoEnrollStr);
            }
            String extrasServerUrl = adminExtras.getString("server_url");
            if (extrasServerUrl != null && !extrasServerUrl.isEmpty()) {
                serverUrl = extrasServerUrl;
            }
            String zteVersion = adminExtras.getString("zte_version", "2.0");
            
            Log.i(TAG, "=== ZTE Admin Extras Found (v2.0) ===");
            Log.i(TAG, "  server_url: " + serverUrl);
            Log.i(TAG, "  auto_enroll: " + autoEnroll);
            Log.i(TAG, "  zte_version: " + zteVersion);
            
            prefs.saveBoolean("zte_provisioned", true);
            prefs.saveString("zte_version", zteVersion);
        } else {
            // Samsung QR provisioning: no admin extras (removed for Samsung compatibility)
            // Also handles Android 13+ which may strip admin extras
            // Auto-enroll using default server URL from BuildConfig
            Log.i(TAG, "=== No admin extras â€” Samsung/Android 13+ QR mode ===");
            Log.i(TAG, "  Auto-enrolling with BuildConfig.SERVER_URL: " + serverUrl);
            prefs.saveBoolean("zte_provisioned", true);
            prefs.saveString("zte_version", "2.0");
            prefs.saveString("provisioning_mode", isSamsung ? "samsung_qr" : "standard_qr");
        }
        
        // Always save server URL
        prefs.setServerUrl(serverUrl);
        
        // Always auto-enroll â€” whether admin extras present or not
        Log.i(TAG, "=== STARTING ZERO TOUCH AUTO-ENROLLMENT v2.0 ===");
        Log.i(TAG, "Device will enroll automatically to: " + serverUrl);
        
        android.widget.Toast.makeText(context, 
                "RR Device Manager: Auto-setup in progress...", 
                android.widget.Toast.LENGTH_LONG).show();
        
        // Start ZTE Service v2.0
        if (adminExtras != null) {
            ZeroTouchEnrollmentService.start(context, adminExtras);
        } else {
            ZeroTouchEnrollmentService.start(context, serverUrl);
        }
    }
    
    /**
     * Apply immediate security restrictions on Device Owner activation.
     * These restrictions are applied BEFORE enrollment to prevent tampering
     * during the enrollment window.
     * 
     * CRITICAL: Also disables Play Protect / Package Verifier immediately.
     * This prevents Play Protect from interfering with the app post-install.
     */
    private void applyImmediateRestrictions(Context context, DevicePolicyManager dpm, ComponentName admin) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try {
                    dpm.clearUserRestriction(admin, "no_install_unknown_sources_globally");
                    Log.i(TAG, "  Cleared no_install_unknown_sources_globally");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not clear global unknown sources: " + e.getMessage());
                }
            }
            
            // Block factory reset
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET);
            Log.i(TAG, "  Restriction: DISALLOW_FACTORY_RESET");
            
            // Block safe boot
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT);
            Log.i(TAG, "  Restriction: DISALLOW_SAFE_BOOT");
            
            // Block USB file transfer (API 30+)
            // NOTE: USB file transfer restriction is safe here â€” it doesn't kill ADB
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER);
                Log.i(TAG, "  Restriction: DISALLOW_USB_FILE_TRANSFER");
            }
            
            // Block USB mounting
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            Log.i(TAG, "  Restriction: DISALLOW_MOUNT_PHYSICAL_MEDIA");

            // === RECOVERY MODE / BOOTLOADER HARDENING (CRITICAL) ===
            // Without these, anyone with hardware key combo + tools can wipe the device.
            // Block OEM unlock â€” bootloader cannot be unlocked => custom recovery impossible
            try {
                dpm.setGlobalSetting(admin, "oem_unlock_enabled", "0");
                Log.i(TAG, "  Hardening: OEM unlock DISABLED (bootloader locked forever)");
            } catch (Exception e) {
                Log.w(TAG, "  Could not disable OEM unlock: " + e.getMessage());
            }

            // Block adding new users (alternate user partition is a known reset bypass)
            try {
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER);
                Log.i(TAG, "  Hardening: DISALLOW_ADD_USER");
            } catch (Exception e) {
                Log.w(TAG, "  Could not block add user: " + e.getMessage());
            }
            // Block network reset path (some Android skins expose factory-style reset here)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_NETWORK_RESET);
                    Log.i(TAG, "  Hardening: DISALLOW_NETWORK_RESET");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not block network reset: " + e.getMessage());
                }
            }

            // === AUTO-WIPE AFTER 4 WRONG PASSWORD ATTEMPTS ===
            // If user enters wrong PIN/pattern/password 4 times, device auto-wipes.
            // The post-wipe FRP screen then locks the device to DEFAULT_FRP_ACCOUNT,
            // effectively bricking it for anyone without the correct Google credentials.
            try {
                dpm.setMaximumFailedPasswordsForWipe(admin, 4);
                Log.i(TAG, "  Hardening: setMaximumFailedPasswordsForWipe=4 (auto-reset+FRP brick)");
            } catch (Exception e) {
                Log.w(TAG, "  Could not set max failed passwords: " + e.getMessage());
            }

            // NOTE: Do NOT disable ADB, USB data signaling, or debugging features here!
            // This runs during provisioning â€” killing USB/ADB here crashes the setup process.
            // These are handled later by applyAllProtections() after enrollment completes.
            
            // CRITICAL: Android 13+ â€” Permit all accessibility services immediately
            // Without this, Android 13+ "Restricted Settings" blocks our accessibility service
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    dpm.setPermittedAccessibilityServices(admin, null); // null = allow ALL
                    Log.i(TAG, "  Android 13+: All accessibility services PERMITTED");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not permit accessibility services: " + e.getMessage());
                }
            }
            
            // NOTE: Do NOT set lock task packages here with only our app.
            // That causes the phone to appear locked during ZTE provisioning.
            // Lock task setup is handled later by applyAllProtections() which
            // whitelists ALL installed apps so the user can use the phone normally.
            
            Log.i(TAG, "Immediate restrictions applied successfully");
        } catch (Exception e) {
            Log.w(TAG, "Could not apply some restrictions: " + e.getMessage());
        }
    }
    
    /**
     * Grant critical runtime permissions silently using Device Owner privilege.
     * This eliminates user permission dialogs during ZTE.
     * Includes Android 13+ (API 33) and Android 14+ (API 34) permissions.
     */
    private void grantCriticalPermissions(Context context, DevicePolicyManager dpm, ComponentName admin) {
        // DISABLED: setPermissionGrantState() crashes the system PermissionController
        // on some MTK / NEXG Android 14 ROMs. The user is prompted for these
        // permissions via the standard runtime dialogs in EnrollmentActivity
        // / MainActivity, so silent granting is unnecessary.
        Log.i(TAG, "grantCriticalPermissions: skipped (handled by user-facing dialogs)");
    }
    
    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        super.onPasswordFailed(context, intent);
        Log.w(TAG, "Password attempt failed");
    }
    
    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.i(TAG, "Password succeeded");
    }
    
    /**
     * Lock the device using Device Admin capabilities
     */
    public static void lockDevice(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = getComponentName(context);
            
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow();
                Log.i(TAG, "Device locked via DevicePolicyManager");
            } else {
                Log.w(TAG, "Cannot lock - Device Admin not active");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error locking device", e);
        }
    }
    
    /**
     * Wipe device data (factory reset)
     * USE WITH EXTREME CAUTION
     */
    public static void wipeDevice(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = getComponentName(context);
            
            if (dpm.isAdminActive(admin)) {
                Log.w(TAG, "Initiating device wipe!");
                dpm.wipeData(0);
            } else {
                Log.w(TAG, "Cannot wipe - Device Admin not active");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error wiping device", e);
        }
    }
    
    /**
     * Set Lock Task packages (Kiosk mode) - Requires Device Owner
     */
    public static void setLockTaskPackages(Context context, String[] packages) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = getComponentName(context);
            
            if (dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.setLockTaskPackages(admin, packages);
                Log.i(TAG, "Lock task packages set");
            } else {
                Log.w(TAG, "Cannot set lock task packages - not Device Owner");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting lock task packages", e);
        }
    }
}
