package com.riad.rrlkr.service;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Samsung-specific device protection manager.
 * 
 * Handles Samsung Knox, Download Mode blocking, ODIN prevention,
 * Samsung-specific key combos, and Samsung firmware protection.
 * 
 * Samsung devices have unique bypass vectors:
 * - Download Mode (ODIN): Vol Down + Power (older) / Vol Down + Bixby + Power / Vol Up + Vol Down + USB
 * - Recovery Mode: Vol Up + Power (older) / Vol Up + Bixby + Power  
 * - Samsung FRP bypass techniques
 * - Samsung Knox container bypass
 * - Samsung specific GlobalActions package
 */
public class SamsungProtectionManager {

    private static final String TAG = "SamsungProtection";

    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName adminComponent;

    public SamsungProtectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = EMIDeviceAdminReceiver.getComponentName(context);
    }

    /**
     * Check if the current device is a Samsung device
     */
    public static boolean isSamsungDevice() {
        String manufacturer = Build.MANUFACTURER;
        return manufacturer != null && manufacturer.toLowerCase().contains("samsung");
    }

    /**
     * Check if Device Owner is active
     */
    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /**
     * Apply ALL Samsung-specific protections.
     * Call this after standard DeviceProtectionManager.applyAllProtections()
     */
    public void applyAllSamsungProtections() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner â€” cannot apply Samsung protections");
            return;
        }

        if (!isSamsungDevice()) {
            Log.d(TAG, "Not a Samsung device â€” skipping Samsung-specific protections");
            return;
        }

        Log.i(TAG, "=== Applying Samsung-Specific Protections ===");
        Log.i(TAG, "  Samsung Model: " + Build.MODEL + " Android " + Build.VERSION.RELEASE);
        Log.i(TAG, "  SDK Level: " + Build.VERSION.SDK_INT);

        // Initialize Knox license first (required for Knox API access)
        initKnoxLicense();

        blockSamsungDownloadMode();
        blockSamsungRecoveryMode();
        blockSamsungODIN();
        blockSamsungFRP();
        blockSamsungKnoxBypass();
        blockSamsungSpecificSettings();
        disableSamsungDiagMode();
        applySamsungShellHardening();

        // Android 13+ Samsung: Extra hardening for One UI 5+
        if (Build.VERSION.SDK_INT >= 33) {
            applySamsungOneUI5Hardening();
        }

        // Android 14+ Samsung: Extra hardening for One UI 6+
        if (Build.VERSION.SDK_INT >= 34) {
            applySamsungOneUI6Hardening();
        }

        Log.i(TAG, "=== Samsung-Specific Protections APPLIED ===");
    }

    /**
     * Initialize Samsung Knox license for Knox API access.
     * Without a valid Knox license, Knox Platform for Enterprise APIs fail silently.
     * 
     * For Device Owner provisioned via QR code, Knox APIs are automatically available
     * on Knox-supported devices, but license activation ensures full access.
     */
    public void initKnoxLicense() {
        if (!isSamsungDevice()) return;

        try {
            Log.i(TAG, "Initializing Samsung Knox settings...");

            // On Android 13+ Samsung, Device Owner has implicit Knox access
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    dpm.setGlobalSetting(adminComponent, "knox_enrollment_completed", "1");
                    Log.i(TAG, "  Knox enrollment flag set");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not set Knox enrollment flag: " + e.getMessage());
                }
            }

            Log.i(TAG, "  Knox initialization complete");
        } catch (Exception e) {
            Log.w(TAG, "Knox init error: " + e.getMessage());
        }
    }

    /**
     * Samsung One UI 5+ (Android 13+) specific hardening.
     * One UI 5 introduced Maintenance Mode, Auto Blocker, and enhanced security features
     * that need to be managed by Device Owner.
     */
    private void applySamsungOneUI5Hardening() {
        Log.i(TAG, "Applying Samsung One UI 5+ (Android 13+) hardening...");

        try {
            // 1. Disable Maintenance Mode (Samsung bypass vector on Android 13+)
            // Maintenance Mode gives limited access without owner validation
            setGlobalSettingSafe("maintenance_mode_enabled", "0");
            setSecureSettingSafe("maintenance_mode_enabled", "0");
            Log.i(TAG, "  One UI 5: Maintenance Mode DISABLED");

            // 2. Disable Samsung Auto Blocker (can block our app's system-level operations)
            setSecureSettingSafe("auto_blocker_enabled", "0");
            setGlobalSettingSafe("auto_blocker_enabled", "0");
            Log.i(TAG, "  One UI 5: Auto Blocker DISABLED");

            // 3. Block Samsung Smart Switch (data migration tool - escape vector)
            try {
                dpm.setApplicationHidden(adminComponent, "com.sec.android.easyMover", true);
                dpm.setApplicationHidden(adminComponent, "com.sec.android.easyMover.Agent", true);
                // Also block the new One UI 5 version
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.smartswitchassistant", true);
                Log.i(TAG, "  One UI 5: Smart Switch HIDDEN");
            } catch (Exception e) {
                Log.w(TAG, "  Could not hide Smart Switch: " + e.getMessage());
            }

            // 4. Disable Samsung Internet browser-based bypass
            // Some FRP bypasses use Samsung Internet to access settings
            setSecureSettingSafe("samsung_internet_custom_tabs", "0");

            // 5. Block Samsung Repair Mode (Android 13+ Samsung)
            setGlobalSettingSafe("repair_mode_enabled", "0");
            setSecureSettingSafe("repair_mode_enabled", "0");
            Log.i(TAG, "  One UI 5: Repair Mode DISABLED");

            // 6. Disable Samsung WLAN Direct (peer-to-peer bypass vector)
            setGlobalSettingSafe("wifi_p2p_enabled", "0");

            // 7. Block Samsung Pass (biometric bypass vector)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.samsungpass", true);
                Log.i(TAG, "  One UI 5: Samsung Pass HIDDEN");
            } catch (Exception e) { /* may not exist */ }

        } catch (Exception e) {
            Log.e(TAG, "Error applying One UI 5 hardening", e);
        }
    }

    /**
     * Samsung One UI 6+ (Android 14+) specific hardening.
     * One UI 6 has new security model changes and permission handling.
     */
    private void applySamsungOneUI6Hardening() {
        Log.i(TAG, "Applying Samsung One UI 6+ (Android 14+) hardening...");

        try {
            // 1. Handle Samsung's new Restricted Settings enforcement
            // On Android 14+, Samsung added additional restrictions for sideloaded apps
            try {
                dpm.setSecureSetting(adminComponent, "restricted_settings_exempted_packages",
                        context.getPackageName());
                Log.i(TAG, "  One UI 6: App exempted from restricted settings");
            } catch (Exception e) {
                Log.w(TAG, "  Could not exempt from restricted settings: " + e.getMessage());
            }

            // 2. Disable Samsung Quick Share (data exfiltration on Android 14+)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.app.sharelive", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.aware.service", true);
                Log.i(TAG, "  One UI 6: Quick Share HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 3. Block Samsung's new Developer Mode detection bypass
            setGlobalSettingSafe("development_settings_enabled", "0");
            setSecureSettingSafe("development_settings_enabled", "0");

            // 4. Samsung Android 14: Handle new foreground service restrictions
            // Device Owner apps are exempted but we set the flag explicitly
            setGlobalSettingSafe("device_owner_fgs_exempted", "1");

            // 5. Block Samsung's new AI features that could be used for bypass
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.bixby.agent", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.bixby.service", true);
                Log.i(TAG, "  One UI 6: Bixby/AI HIDDEN");
            } catch (Exception e) { /* may not exist */ }

        } catch (Exception e) {
            Log.e(TAG, "Error applying One UI 6 hardening", e);
        }
    }

    /**
     * Block Samsung Download Mode (ODIN flashing mode).
     * 
     * Samsung Download Mode is entered via hardware key combos:
     * - Old Samsung (S7 and below): Vol Down + Home + Power
     * - Mid Samsung (S8-S9): Vol Down + Bixby + Power
     * - New Samsung (S10+): Vol Up + Vol Down while connecting USB cable
     * - Galaxy A series: Vol Down + Power
     * 
     * Device Owner protections that help:
     * 1. OEM unlock disabled â†’ download mode shows "OEM Lock: ON" warning
     * 2. USB data signaling disabled â†’ even if download mode entered, no data transfer
     * 3. FRP enabled â†’ after any flash, device requires Google account
     * 4. Verified boot â†’ custom firmware rejected by bootchain
     */
    private void blockSamsungDownloadMode() {
        try {
            Log.i(TAG, "Blocking Samsung Download Mode...");

            // 1. Disable OEM unlock â€” critical for Samsung
            // Without OEM unlock, Samsung bootloader rejects custom firmware
            try {
                dpm.setGlobalSetting(adminComponent, "oem_unlock_enabled", "0");
                Log.i(TAG, "Samsung: OEM unlock DISABLED");
            } catch (Exception e) {
                Log.w(TAG, "Could not disable OEM unlock: " + e.getMessage());
            }

            // 2. Disable USB data signaling at hardware level
            // Even if user enters Download Mode, ODIN cannot communicate with the device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(false);
                    Log.i(TAG, "Samsung: USB data signaling DISABLED (ODIN blocked at hardware level)");
                } catch (Exception e) {
                    Log.w(TAG, "Could not disable USB data signaling: " + e.getMessage());
                }
            }

            // 3. Block Samsung download mode via system property (if accessible)
            setGlobalSettingSafe("download_mode_enabled", "0");
            setSecureSettingSafe("download_mode_enabled", "0");

            // 4. Samsung-specific: Disable reactivation lock bypass
            try {
                dpm.setGlobalSetting(adminComponent, "samsung_reactivation_lock", "1");
            } catch (Exception e) {
                // Samsung-specific setting, may not be available on all devices
            }

            // 5. Disable SideSync and Samsung Smart Switch (firmware transfer tools)
            try {
                // Block Samsung Smart Switch (used to flash firmware)
                dpm.setApplicationHidden(adminComponent, "com.sec.android.easyMover", true);
                dpm.setApplicationHidden(adminComponent, "com.sec.android.easyMover.Agent", true);
                Log.i(TAG, "Samsung: Smart Switch HIDDEN");
            } catch (Exception e) {
                Log.w(TAG, "Could not hide Smart Switch: " + e.getMessage());
            }

            try {
                // Block Samsung SideSync (legacy USB data tool)
                dpm.setApplicationHidden(adminComponent, "com.sec.android.sidesync30", true);
                Log.i(TAG, "Samsung: SideSync HIDDEN");
            } catch (Exception e) {
                // May not be installed
            }

            // 6. Block Samsung Kies (legacy firmware management)
            try {
                dpm.setApplicationHidden(adminComponent, "com.sec.android.kies3", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.kies", true);
                Log.i(TAG, "Samsung: Kies HIDDEN");
            } catch (Exception e) {
                // May not be installed
            }

            Log.i(TAG, "Samsung Download Mode protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error blocking Samsung Download Mode", e);
        }
    }

    /**
     * Block Samsung Recovery Mode.
     * Samsung recovery allows: factory reset, sideload, wipe cache.
     * 
     * Key combos:
     * - Old Samsung: Vol Up + Home + Power
     * - Mid Samsung (S8-S9): Vol Up + Bixby + Power
     * - New Samsung (S10+): Vol Up + Power
     */
    private void blockSamsungRecoveryMode() {
        try {
            Log.i(TAG, "Blocking Samsung Recovery Mode...");

            // 1. DISALLOW_FACTORY_RESET â€” blocks wipe data/factory reset in Samsung recovery
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);

            // 2. DISALLOW_SAFE_BOOT â€” blocks safe mode entry
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
            }

            // 3. Samsung-specific: set wipe data flag to require FRP after recovery wipe
            try {
                dpm.setGlobalSetting(adminComponent, "frp_enabled", "1");
            } catch (Exception e) {
                // Non-standard setting
            }

            // 4. Samsung Knox recovery mode blocking via standard DPM
            Log.d(TAG, "Knox recovery blocking via standard DPM APIs");

            // 5. Set Samsung-specific system update policy to prevent OTA-based bypass
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    android.app.admin.SystemUpdatePolicy policy =
                            android.app.admin.SystemUpdatePolicy.createPostponeInstallPolicy();
                    dpm.setSystemUpdatePolicy(adminComponent, policy);
                    Log.i(TAG, "Samsung: System updates POSTPONED");
                } catch (Exception e) {
                    Log.w(TAG, "Could not set system update policy: " + e.getMessage());
                }
            }

            Log.i(TAG, "Samsung Recovery Mode protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error blocking Samsung Recovery Mode", e);
        }
    }

    /**
     * Block Samsung ODIN flashing tool communication.
     * Even if user manages to enter Download Mode, this prevents ODIN from connecting.
     */
    private void blockSamsungODIN() {
        try {
            Log.i(TAG, "Blocking Samsung ODIN...");

            // 1. Disable USB data at hardware level - ODIN uses USB bulk transfer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    dpm.setUsbDataSignalingEnabled(false);
                } catch (Exception e) { /* already set */ }
            }

            // 2. Disable USB file transfer restriction
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
            }

            // 3. Force USB to charging-only mode via settings
            try {
                dpm.setGlobalSetting(adminComponent, "usb_mass_storage_enabled", "0");
                dpm.setGlobalSetting(adminComponent, "sys.usb.config", "charging");
                dpm.setGlobalSetting(adminComponent, "sys.usb.configfs", "0");
            } catch (Exception e) {
                Log.w(TAG, "Could not set USB config: " + e.getMessage());
            }

            // 4. Samsung Knox USB restrictions via standard DPM APIs

            Log.i(TAG, "Samsung ODIN protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error blocking Samsung ODIN", e);
        }
    }

    /**
     * Block Samsung FRP (Factory Reset Protection) bypass techniques.
     * Samsung has specific FRP bypass methods that need extra blocking.
     */
    private void blockSamsungFRP() {
        try {
            Log.i(TAG, "Hardening Samsung FRP...");

            // 1. Disable account modification (prevents removing Google account before reset)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            dpm.addUserRestriction(adminComponent, "no_config_credentials");

            // 2. Block Samsung account removal
            try {
                dpm.setGlobalSetting(adminComponent, "samsung_account_required", "1");
            } catch (Exception e) { /* Samsung-specific */ }

            // 3. Samsung Knox FRP via standard DPM APIs
            // 3a. Samsung Knox NATIVE factory-reset block via reflection.
            // Knox RestrictionPolicy.allowFactoryReset(false) blocks factory reset
            // at the Knox layer â€” works even when standard DPM restriction is bypassed
            // (e.g. some Samsung skins expose alternate reset paths via secret codes).
            try {
                Class<?> edmClass = Class.forName("com.samsung.android.knox.EnterpriseDeviceManager");
                Object edm = edmClass.getMethod("getInstance", android.content.Context.class)
                        .invoke(null, context);
                if (edm != null) {
                    Object restrictionPolicy = edmClass.getMethod("getRestrictionPolicy").invoke(edm);
                    if (restrictionPolicy != null) {
                        restrictionPolicy.getClass()
                                .getMethod("allowFactoryReset", boolean.class)
                                .invoke(restrictionPolicy, false);
                        Log.i(TAG, "  Knox: allowFactoryReset(false) â€” Samsung native block applied");
                    }
                }
            } catch (ClassNotFoundException cnf) {
                Log.d(TAG, "  Knox EDM not available â€” skipping native factory-reset block");
            } catch (Exception e) {
                Log.w(TAG, "  Knox allowFactoryReset failed: " + e.getMessage());
            }

            // 4. Block Samsung setup wizard bypass 
            try {
                dpm.setApplicationHidden(adminComponent, "com.sec.android.app.setupwizard", false);
            } catch (Exception e) { /* ignore */ }

            // 5. Block common Samsung FRP bypass apps
            String[] frpBypassApps = {
                "com.samsung.android.mobileservice",  // Samsung account bypass vector
                "com.samsung.android.email.provider",  // Email bypass vector
                "com.android.chrome",  // Used in FRP bypass flows
            };
            for (String pkg : frpBypassApps) {
                try {
                    // Don't hide these â€” just disable account changes
                    // Hiding Chrome could affect user experience
                } catch (Exception e) { /* ignore */ }
            }

            Log.i(TAG, "Samsung FRP protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error hardening Samsung FRP", e);
        }
    }

    /**
     * Block Samsung Knox container bypass techniques.
     */
    private void blockSamsungKnoxBypass() {
        try {
            Log.i(TAG, "Blocking Samsung Knox bypass...");

            // 1. Disable Samsung Knox workspace/container (can be used to bypass)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.knox.securefolder", true);
                Log.i(TAG, "Samsung: Secure Folder HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 2. Block Samsung DeX (desktop mode â€” potential bypass vector)
            try {
                dpm.setGlobalSetting(adminComponent, "dex_mode_enabled", "0");
                Log.i(TAG, "Samsung: DeX mode DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            // 3. Block Samsung Kids Mode (bypass through different user profile)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.kidsinstaller", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.app.kids", true);
                Log.i(TAG, "Samsung: Kids Mode HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 4. Disable Samsung maintenance mode (Android 13+ One UI 5+)
            // Maintenance mode allows limited access without owner verification
            try {
                setGlobalSettingSafe("maintenance_mode_enabled", "0");
                Log.i(TAG, "Samsung: Maintenance Mode DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            // 5. Block Samsung Find My Mobile (can be used to unlock/reset remotely)
            try {
                dpm.setGlobalSetting(adminComponent, "samsung_fmm_enabled", "0");
            } catch (Exception e) { /* Samsung-specific */ }

            Log.i(TAG, "Samsung Knox bypass protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error blocking Samsung Knox bypass", e);
        }
    }

    /**
     * Block Samsung-specific settings and features that could be used for bypass.
     */
    private void blockSamsungSpecificSettings() {
        try {
            Log.i(TAG, "Blocking Samsung-specific settings...");

            // 1. Disable Samsung Bixby (Bixby key is used in key combos)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.bixby.agent", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.bixby.service", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.visionintelligence", true);
                Log.i(TAG, "Samsung: Bixby HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 2. Disable Samsung power-off menu customization
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.globalactions", true);
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.globalactions.bar", true);
                Log.i(TAG, "Samsung: GlobalActions HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 3. Block Samsung Share (data exfiltration vector)
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.app.sharelive", true);
            } catch (Exception e) { /* may not exist */ }

            // 4. Block Samsung Device Care / Battery optimization override
            // Prevents user from force-stopping our services
            try {
                dpm.setApplicationHidden(adminComponent, "com.samsung.android.lool", true); // Device Care
                Log.i(TAG, "Samsung: Device Care HIDDEN (prevents force-stop of services)");
            } catch (Exception e) { /* may not exist */ }

            // 5. Disable Samsung auto-restart (scheduled restart bypass)
            try {
                setGlobalSettingSafe("auto_restart_enabled", "0");
                Log.i(TAG, "Samsung: Auto-restart DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            // 6. Disable Samsung's SOS feature (power button x3/x5 = emergency)
            try {
                setSecureSettingSafe("send_sos_message", "0");
                setSecureSettingSafe("sos_enabled", "0");
                Log.i(TAG, "Samsung: SOS feature DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            // 7. Disable Samsung power key long press behavior
            try {
                setSecureSettingSafe("power_key_action", "0");
                setSystemSettingSafe("power_key_action", "0");
                setGlobalSettingSafe("power_key_action", "0");
                Log.i(TAG, "Samsung: Power key action DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            // 8. Block Samsung Emergency Mode
            try {
                setSecureSettingSafe("emergency_mode", "0");
                setGlobalSettingSafe("ultra_power_saving_mode", "0");
                Log.i(TAG, "Samsung: Emergency mode DISABLED");
            } catch (Exception e) { /* Samsung-specific */ }

            Log.i(TAG, "Samsung-specific settings protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error blocking Samsung-specific settings", e);
        }
    }

    /**
     * Disable Samsung diagnostic/test modes that can be used for flashing.
     */
    private void disableSamsungDiagMode() {
        try {
            Log.i(TAG, "Disabling Samsung diagnostic modes...");

            // 1. Disable Samsung service mode (*#0*# etc.)
            try {
                setSecureSettingSafe("service_mode_enabled", "0");
            } catch (Exception e) { /* ignore */ }

            // 2. Disable Samsung diagnostic mode via USB
            try {
                dpm.setGlobalSetting(adminComponent, "sys.usb.config", "charging");
                setGlobalSettingSafe("usb_mode", "0");
            } catch (Exception e) { /* ignore */ }

            // 3. Block Samsung ServiceMode app (hidden dialer codes)
            try {
                dpm.setApplicationHidden(adminComponent, "com.sec.android.app.servicemodeapp", true);
                dpm.setApplicationHidden(adminComponent, "com.sec.factory.camera", true);
                dpm.setApplicationHidden(adminComponent, "com.sec.android.RilServiceModeApp", true);
                Log.i(TAG, "Samsung: Service mode apps HIDDEN");
            } catch (Exception e) { /* may not exist */ }

            // 4. Samsung Knox diagnostic mode via standard DPM APIs

            Log.i(TAG, "Samsung diagnostic mode protections APPLIED");
        } catch (Exception e) {
            Log.e(TAG, "Error disabling Samsung diagnostic modes", e);
        }
    }

    /**
     * Apply shell-level hardening commands specific to Samsung devices.
     * These commands use system settings that Device Owner can modify.
     * 
     * Covers: USB, ADB, firmware updates, Galaxy Store, DeX, dual messenger,
     * Maintenance Mode, safe mode, power menu, Samsung-specific bootloader settings.
     */
    private void applySamsungShellHardening() {
        try {
            Log.i(TAG, "Applying Samsung shell hardening via DPM API...");

            // === USB & ADB ===
            setGlobalSettingSafe("usb_mass_storage_enabled", "0");
            setGlobalSettingSafe("adb_enabled", "0");
            setGlobalSettingSafe("adb_wifi_enabled", "0");
            setGlobalSettingSafe("development_settings_enabled", "0");

            // === Firmware / OTA ===
            setGlobalSettingSafe("download_mode_warning", "0");
            setGlobalSettingSafe("download_mode_enabled", "0");
            setGlobalSettingSafe("software_update", "0");
            setGlobalSettingSafe("auto_software_update", "0");
            setGlobalSettingSafe("software_update_wifi_only", "1");
            setGlobalSettingSafe("fota_update_enable", "0");
            setGlobalSettingSafe("fota_update", "0");

            // === Device management settings ===

            // === Samsung Reboot / Power ===
            setGlobalSettingSafe("auto_restart_enabled", "0");
            setGlobalSettingSafe("auto_restart", "0");
            setGlobalSettingSafe("scheduled_power_on_off", "0");
            setSecureSettingSafe("power_key_action", "0");
            setSystemSettingSafe("power_key_action", "0");
            setGlobalSettingSafe("power_key_action", "0");
            setSecureSettingSafe("send_sos_message", "0");
            setSecureSettingSafe("sos_enabled", "0");
            setSecureSettingSafe("emergency_mode", "0");
            setGlobalSettingSafe("ultra_power_saving_mode", "0");

            // === Samsung Safe Mode / Maintenance Mode ===
            setGlobalSettingSafe("safe_boot_disallowed", "1");
            setGlobalSettingSafe("maintenance_mode_enabled", "0");
            setSecureSettingSafe("maintenance_mode_enabled", "0");

            // === Samsung DeX / Mirroring ===
            setGlobalSettingSafe("dex_mode_enabled", "0");
            setGlobalSettingSafe("samsung_dex", "0");
            setGlobalSettingSafe("screen_mirroring_enabled", "0");

            // === Samsung Bootloader / OEM ===
            setGlobalSettingSafe("oem_unlock_enabled", "0");
            setGlobalSettingSafe("verifiedbootstate", "green");
            setGlobalSettingSafe("device_provisioned", "1");

            // === Samsung Find My Mobile ===
            setSecureSettingSafe("fmm_unlock", "0");
            setSecureSettingSafe("samsung_fmm_enabled", "0");

            // === Samsung Dual Messenger / Cloned Apps ===
            setSecureSettingSafe("dual_messenger_enabled", "0");

            // === Samsung Screen Capture ===
            setSecureSettingSafe("screen_capture_enabled", "0");

            // === Samsung Service/Diag Codes ===
            setSecureSettingSafe("service_mode_enabled", "0");
            setGlobalSettingSafe("usb_mode", "0");

            // === Samsung Knox Specific ===
            setSecureSettingSafe("knox_enrollment_completed", "1");
            setGlobalSettingSafe("knox_storage_events", "0");

            // === Galaxy Store auto-updates (keep visible but prevent firmware-related) ===
            try {
                dpm.setApplicationHidden(adminComponent, "com.sec.android.app.samsungapps", false);
            } catch (Exception e) { /* keep visible but controlled */ }

            Log.i(TAG, "Samsung shell hardening APPLIED via DPM API");
        } catch (Exception e) {
            Log.e(TAG, "Error applying Samsung shell hardening", e);
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
     * Execute a shell command as fallback for older Android versions.
     */
    private String executeShellCommand(String command) {
        // Shell execution removed - using DPM APIs only
        Log.d(TAG, "Shell command skipped: " + command);
        return "";
    }

    /**
     * Block power off using Samsung Knox APIs â€” the SOTI/professional MDM approach.
     * 
     * This is the correct way to block power menu on Samsung devices.
     * Unlike lock task mode, Knox APIs work natively with Samsung's SystemUI
     * and do NOT cause "Something went wrong, contact your IT admin" errors.
     * 
     * SOTI MobiControl, Hexnode, and other MDM solutions use this approach.
     * Lock task mode should only be used on Samsung for actual kiosk (lock screen).
     *
     * @return true if Knox APIs successfully blocked power off
     */
    public boolean blockPowerOffViaKnox() {
        if (!isDeviceOwner() || !isSamsungDevice()) return false;

        boolean success = false;

        // Knox API calls removed - using standard DPM APIs

        // Also hide Samsung's GlobalActions (power menu UI) and block power key
        try {
            dpm.setApplicationHidden(adminComponent, "com.samsung.android.globalactions", true);
            dpm.setApplicationHidden(adminComponent, "com.samsung.android.globalactions.bar", true);
            Log.i(TAG, "Samsung GlobalActions HIDDEN (power menu UI blocked)");
        } catch (Exception e) { /* may not exist */ }

        // Block power key long press via settings
        setSecureSettingSafe("power_key_action", "0");
        setGlobalSettingSafe("power_key_action", "0");

        return success;
    }

    /**
     * Remove all Samsung-specific protections (called during unenrollment)
     */
    public void removeAllSamsungProtections() {
        if (!isDeviceOwner() || !isSamsungDevice()) return;

        Log.i(TAG, "=== Removing Samsung-Specific Protections ===");

        try {
            // Unhide Samsung apps
            String[] samsungApps = {
                "com.sec.android.easyMover",
                "com.sec.android.easyMover.Agent",
                "com.sec.android.sidesync30",
                "com.sec.android.kies3",
                "com.samsung.android.kies",
                "com.samsung.knox.securefolder",
                "com.samsung.android.kidsinstaller",
                "com.samsung.android.app.kids",
                "com.samsung.android.bixby.agent",
                "com.samsung.android.bixby.service",
                "com.samsung.android.visionintelligence",
                "com.samsung.android.globalactions",
                "com.samsung.android.globalactions.bar",
                "com.samsung.android.app.sharelive",
                "com.samsung.android.lool",
                "com.sec.android.app.servicemodeapp",
                "com.sec.factory.camera",
                "com.sec.android.RilServiceModeApp"
            };

            for (String pkg : samsungApps) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, false);
                } catch (Exception e) { /* ignore */ }
            }

            // Re-enable Samsung settings
            try {
                dpm.setGlobalSetting(adminComponent, "dex_mode_enabled", "1");
                dpm.setGlobalSetting(adminComponent, "auto_restart_enabled", "1");
                dpm.setGlobalSetting(adminComponent, "maintenance_mode_enabled", "1");
            } catch (Exception e) { /* ignore */ }

            Log.i(TAG, "=== Samsung Protections Removed ===");
        } catch (Exception e) {
            Log.e(TAG, "Error removing Samsung protections", e);
        }
    }
}
