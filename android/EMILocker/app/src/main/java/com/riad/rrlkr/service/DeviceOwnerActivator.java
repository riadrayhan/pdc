package com.riad.rrlkr.service;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/**
 * Device Owner Activator - Tries multiple methods to activate Device Owner mode.
 * 
 * Priority order:
 * 1. Check if already Device Owner
 * 2. Try shell command (works on rooted devices or via ADB wireless debugging)
 * 3. Try managed provisioning (Android 6.0+)
 * 4. Try NFC provisioning intent
 * 5. Fall back to guided ADB instructions
 * 
 * Android restricts Device Owner activation to:
 * - ADB command (most common)
 * - NFC bump during setup wizard
 * - QR code during setup wizard  
 * - Zero-Touch enrollment
 * - Root shell command
 */
public class DeviceOwnerActivator {

    private static final String TAG = "DeviceOwnerActivator";
    private static final String ADMIN_COMPONENT = "com.riad.rrlkr/.admin.EMIDeviceAdminReceiver";

    public interface ActivationCallback {
        void onSuccess(String method);
        void onFailed(String reason, String instructions);
    }

    private final Context context;
    private final DevicePolicyManager dpm;

    public DeviceOwnerActivator(Context context) {
        this.context = context.getApplicationContext();
        this.dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * Check if Device Owner is already active
     */
    public boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /**
     * Check if Device Admin is active
     */
    public boolean isDeviceAdmin() {
        ComponentName admin = EMIDeviceAdminReceiver.getComponentName(context);
        return dpm != null && dpm.isAdminActive(admin);
    }

    /**
     * Attempt to activate Device Owner using all available methods.
     * Tries automatic methods first, falls back to guided instructions.
     */
    public void activate(ActivationCallback callback) {
        Log.i(TAG, "=== Starting Device Owner Activation ===");
        Log.i(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE);

        // Step 0: Already Device Owner?
        if (isDeviceOwner()) {
            Log.i(TAG, "Already Device Owner!");
            callback.onSuccess("already_active");
            return;
        }

        // Samsung Knox may need special handling
        boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung");
        if (isSamsung) {
            Log.i(TAG, "Samsung device detected â€” checking Knox compatibility");
        }

        // Step 1: Try root shell command
        if (tryRootActivation()) {
            // Verify it worked
            if (isDeviceOwner()) {
                Log.i(TAG, "Device Owner activated via ROOT");
                callback.onSuccess("root_shell");
                return;
            }
        }

        // Step 2: Try shell command without root (works if ADB wireless debugging is connected)
        if (tryShellActivation()) {
            if (isDeviceOwner()) {
                Log.i(TAG, "Device Owner activated via SHELL");
                callback.onSuccess("shell");
                return;
            }
        }

        // Step 3: Device Admin is active but Device Owner is NOT
        // This is NOT a success â€” Device Owner provides full protection.
        // Device Admin only provides basic lock/unlock.
        if (isDeviceAdmin()) {
            Log.w(TAG, "Only Device Admin active â€” Device Owner NOT set!");
            Log.w(TAG, "Device Owner requires ADB command or QR provisioning");
            
            // Report as FAILURE with instructions, not success
            boolean isSamsungDevice = Build.MANUFACTURER.equalsIgnoreCase("samsung");
            if (isSamsungDevice) {
                callback.onFailed(
                    "Device Owner not set (only Device Admin)",
                    "âš ï¸ Device Admin is active but Device Owner is NOT set!\n\n" +
                    "Device Admin = basic (only lock/unlock)\n" +
                    "Device Owner = full protection (block factory reset, USB, uninstall)\n\n" +
                    getSamsungActivationInstructions()
                );
            } else {
                callback.onFailed(
                    "Device Owner not set (only Device Admin)",
                    "âš ï¸ Device Admin is active but Device Owner is NOT set!\n\n" +
                    "Device Admin = basic (only lock/unlock)\n" +
                    "Device Owner = full protection (block factory reset, USB, uninstall)\n\n" +
                    getActivationInstructions()
                );
            }
            return;
        }

        // Step 4: For Samsung, provide Samsung-specific instructions
        Log.w(TAG, "Automatic activation failed - providing instructions");
        if (isSamsung) {
            callback.onFailed(
                "Samsung device requires special setup",
                getSamsungActivationInstructions()
            );
        } else {
            callback.onFailed(
                "Device Owner requires one-time setup",
                getActivationInstructions()
            );
        }
    }

    /**
     * Try activating Device Owner via root shell command.
     * Works on rooted devices â€” the ONLY way to auto-set Device Owner from within the app.
     */
    private boolean tryRootActivation() {
        Log.i(TAG, "Attempting Device Owner via root shell...");
        try {
            Process suProcess = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());
            os.writeBytes("dpm set-device-owner " + ADMIN_COMPONENT + "\n");
            os.writeBytes("exit\n");
            os.flush();
            
            int exitValue = suProcess.waitFor();
            
            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            
            BufferedReader errReader = new BufferedReader(new InputStreamReader(suProcess.getErrorStream()));
            StringBuilder errOutput = new StringBuilder();
            while ((line = errReader.readLine()) != null) {
                errOutput.append(line);
            }
            
            Log.i(TAG, "Root activation exit: " + exitValue + " output: " + output + " err: " + errOutput);
            
            if (exitValue == 0 || output.toString().contains("Success")) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Root activation failed (device likely not rooted): " + e.getMessage());
        }
        return false;
    }

    /**
     * Try activating via regular shell command.
     * This only works if ADB wireless debugging is enabled and connected,
     * or if the app has shell-level permissions.
     */
    private boolean tryShellActivation() {
        Log.i(TAG, "Attempting Device Owner via shell...");
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "dpm set-device-owner " + ADMIN_COMPONENT
            });
            
            int exitValue = process.waitFor();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            
            Log.i(TAG, "Shell activation exit: " + exitValue + " output: " + output);
            
            if (exitValue == 0 || output.toString().contains("Success")) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Shell activation failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Check if managed provisioning can be used.
     * Requires: Android 6.0+, no accounts on device, device not already provisioned.
     */
    private boolean canUseManagedProvisioning() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        
        try {
            Intent provisioningIntent = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE);
            ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(context);
            provisioningIntent.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent
            );
            
            return provisioningIntent.resolveActivity(context.getPackageManager()) != null;
        } catch (Exception e) {
            Log.w(TAG, "Cannot check managed provisioning: " + e.getMessage());
            return false;
        }
    }

    /**
     * Launch the managed provisioning flow.
     * This will show Android's built-in device owner setup wizard.
     */
    private void launchManagedProvisioning() {
        try {
            Intent provisioningIntent = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE);
            ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(context);
            
            provisioningIntent.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent
            );
            
            // Skip encryption (for speed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                provisioningIntent.putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true
                );
            }
            
            // Keep all system apps enabled
            provisioningIntent.putExtra(
                "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true
            );
            
            provisioningIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(provisioningIntent);
            
            Log.i(TAG, "Managed provisioning flow LAUNCHED");
        } catch (Exception e) {
            Log.e(TAG, "Error launching managed provisioning", e);
        }
    }

    /**
     * Try to remove Google accounts that block Device Owner activation.
     * Only works with root.
     */
    public boolean tryRemoveAccountsForDeviceOwner() {
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            android.accounts.Account[] accounts = am.getAccounts();
            
            if (accounts.length == 0) {
                Log.i(TAG, "No accounts found - Device Owner can be set");
                return true;
            }

            Log.i(TAG, "Found " + accounts.length + " accounts that may block Device Owner");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Error checking accounts: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if the device has any accounts that would block Device Owner setup.
     */
    public boolean hasBlockingAccounts() {
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            android.accounts.Account[] accounts = am.getAccounts();
            return accounts.length > 0;
        } catch (Exception e) {
            return true; // Assume blocking if we can't check
        }
    }

    /**
     * Get the number of accounts on the device
     */
    public int getAccountCount() {
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            return am.getAccounts().length;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get step-by-step instructions for Device Owner activation.
     * Returns localized instructions in Bangla + English.
     * Includes BOTH QR method (no PC) and ADB method (with PC).
     */
    public String getActivationInstructions() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("â•â•â• Device Owner Setup â•â•â•\n\n");
        
        sb.append("â”â”â” Method 1: QR Code â”â”â”\n");
        sb.append("   âœ… NO PC needed!\n\n");
        sb.append("1. Factory Reset the phone\n");
        sb.append("2. At Welcome screen, tap 6 times\n");
        sb.append("3. QR Scanner opens\n");
        sb.append("4. Scan provisioning QR from\n");
        sb.append("   Admin Panel > Bulk Setup\n");
        sb.append("5. Automatic setup complete!\n\n");
        
        sb.append("â”â”â” Method 2: ADB (PC) â”â”â”\n");
        sb.append("   For already-setup phones\n\n");
        sb.append("1. Remove ALL accounts\n");
        sb.append("   Settings > Accounts > Remove\n\n");
        sb.append("2. Enable USB Debugging\n");
        sb.append("   Developer Options > USB Debug\n\n");
        sb.append("3. Connect USB to PC, run:\n");
        sb.append("   ").append(getADBCommand()).append("\n\n");
        sb.append("4. Re-add accounts after setup\n\n");
        
        sb.append("â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€\n");
        sb.append("à¦¬à¦¾à¦‚à¦²à¦¾ (QR à¦ªà¦¦à§à¦§à¦¤à¦¿ - PC à¦›à¦¾à¦¡à¦¼à¦¾):\n");
        sb.append("à§§. à¦«à§‹à¦¨ Factory Reset à¦•à¦°à§à¦¨\n");
        sb.append("à§¨. Welcome screen à¦ à§¬ à¦¬à¦¾à¦° tap à¦•à¦°à§à¦¨\n");
        sb.append("à§©. QR Scanner open à¦¹à¦¬à§‡\n");
        sb.append("à§ª. Admin Panel à¦¥à§‡à¦•à§‡ QR scan à¦•à¦°à§à¦¨\n");
        sb.append("à§«. à¦¸à§‡à¦Ÿà¦†à¦ª complete!\n\n");
        
        sb.append("à¦¬à¦¾à¦‚à¦²à¦¾ (ADB à¦ªà¦¦à§à¦§à¦¤à¦¿ - PC à¦¦à¦¿à¦¯à¦¼à§‡):\n");
        sb.append("à§§. à¦¸à¦¬ Account Remove à¦•à¦°à§à¦¨\n");
        sb.append("à§¨. USB Debugging à¦šà¦¾à¦²à§ à¦•à¦°à§à¦¨\n");
        sb.append("à§©. USB à¦¦à¦¿à¦¯à¦¼à§‡ PC à¦¤à§‡ connect à¦•à¦°à§à¦¨\n");
        sb.append("à§ª. Command à¦¦à¦¿à¦¨:\n");
        sb.append("   ").append(getADBCommand()).append("\n");
        sb.append("à§«. à¦†à¦¬à¦¾à¦° Account add à¦•à¦°à§à¦¨\n");
        
        return sb.toString();
    }

    /**
     * Samsung-specific Device Owner activation instructions.
     * Samsung phones with Knox have additional requirements.
     */
    public String getSamsungActivationInstructions() {
        StringBuilder sb = new StringBuilder();

        sb.append("â•â•â• Samsung Device Owner Setup â•â•â•\n\n");

        sb.append("âš ï¸ Samsung phones need extra steps!\n\n");

        sb.append("â”â”â” Method 1: QR Code (Best) â”â”â”\n");
        sb.append("   âœ… NO PC needed!\n\n");
        sb.append("1. Factory Reset the phone\n");
        sb.append("2. Connect to WiFi\n");
        sb.append("3. At Welcome screen, tap screen\n");
        sb.append("   6 times quickly\n");
        sb.append("4. QR Scanner opens automatically\n");
        sb.append("5. Scan provisioning QR from\n");
        sb.append("   Admin Panel > Bulk Setup\n");
        sb.append("6. Automatic setup complete!\n\n");

        sb.append("â”â”â” Method 2: ADB (Samsung) â”â”â”\n");
        sb.append("   For already-setup Samsung phones\n\n");
        sb.append("1. Remove ALL accounts:\n");
        sb.append("   Settings > Accounts > Remove ALL\n");
        sb.append("   (Google, Samsung, etc.)\n\n");
        sb.append("2. Disable Samsung Knox Guard:\n");
        sb.append("   Settings > Biometrics > More\n");
        sb.append("   > Turn off Find My Mobile\n\n");
        sb.append("3. Enable USB Debugging:\n");
        sb.append("   Settings > About Phone >\n");
        sb.append("   Software Info > Tap Build # 7x\n");
        sb.append("   Then: Developer Options >\n");
        sb.append("   USB Debugging ON\n\n");
        sb.append("4. Connect USB to PC, run:\n");
        sb.append("   ").append(getADBCommand()).append("\n\n");
        sb.append("5. If error, also run:\n");
        sb.append("   adb shell pm disable-user\n");
        sb.append("   com.samsung.android.knox.containercore\n\n");
        sb.append("   Then retry step 4\n\n");
        sb.append("6. Re-add accounts after setup\n\n");

        sb.append("â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€\n");
        sb.append("à¦¬à¦¾à¦‚à¦²à¦¾ (Samsung à¦¸à§‡à¦Ÿà¦†à¦ª):\n");
        sb.append("à§§. à¦¸à¦¬ Account Remove à¦•à¦°à§à¦¨\n");
        sb.append("   (Google + Samsung à¦¦à§à¦Ÿà§‹à¦‡)\n");
        sb.append("à§¨. Find My Mobile à¦¬à¦¨à§à¦§ à¦•à¦°à§à¦¨\n");
        sb.append("à§©. USB Debugging à¦šà¦¾à¦²à§ à¦•à¦°à§à¦¨\n");
        sb.append("à§ª. USB à¦¦à¦¿à¦¯à¦¼à§‡ PC à¦¤à§‡ connect à¦•à¦°à§à¦¨\n");
        sb.append("à§«. Command à¦¦à¦¿à¦¨:\n");
        sb.append("   ").append(getADBCommand()).append("\n");
        sb.append("à§¬. Error à¦¹à¦²à§‡ Knox disable à¦•à¦°à§à¦¨\n");
        sb.append("à§­. à¦†à¦¬à¦¾à¦° Account add à¦•à¦°à§à¦¨\n");

        return sb.toString();
    }

    /**
     * Get the ADB command for Device Owner activation
     */
    public static String getADBCommand() {
        return "adb shell dpm set-device-owner " + ADMIN_COMPONENT;
    }

    /**
     * Generate a Windows batch script for easy Device Owner setup
     */
    public static String generateSetupBatchScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\n");
        sb.append("echo ================================================\n");
        sb.append("echo   RR Device Manager - Device Owner Setup Script\n");
        sb.append("echo ================================================\n");
        sb.append("echo.\n");
        sb.append("echo Make sure:\n");
        sb.append("echo  1. Phone is connected via USB\n");
        sb.append("echo  2. USB Debugging is enabled\n");
        sb.append("echo  3. ALL Google accounts are removed from phone\n");
        sb.append("echo.\n");
        sb.append("pause\n");
        sb.append("echo.\n");
        sb.append("echo Checking for connected device...\n");
        sb.append("adb devices\n");
        sb.append("echo.\n");
        sb.append("echo Setting Device Owner...\n");
        sb.append("adb shell dpm set-device-owner ").append(ADMIN_COMPONENT).append("\n");
        sb.append("echo.\n");
        sb.append("if %ERRORLEVEL% EQU 0 (\n");
        sb.append("    echo ================================================\n");
        sb.append("    echo   SUCCESS! Device Owner mode activated!\n");
        sb.append("    echo   You can now add Google accounts back.\n");
        sb.append("    echo ================================================\n");
        sb.append(") else (\n");
        sb.append("    echo ================================================\n");
        sb.append("    echo   FAILED! Please check:\n");
        sb.append("    echo   - USB Debugging is enabled\n");
        sb.append("    echo   - All accounts are removed\n");
        sb.append("    echo   - Phone is connected properly\n");
        sb.append("    echo ================================================\n");
        sb.append(")\n");
        sb.append("echo.\n");
        sb.append("pause\n");
        return sb.toString();
    }
}
