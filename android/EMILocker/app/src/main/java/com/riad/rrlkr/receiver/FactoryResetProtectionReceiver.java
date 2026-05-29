package com.riad.rrlkr.receiver;

import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.util.Log;

import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.utils.DeviceFingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory Reset Protection helper.
 * NOT a device admin â€” only EMIDeviceAdminReceiver should be the device admin.
 * Having two device admin receivers causes Samsung QR provisioning to fail.
 * FRP setup is triggered by EMIDeviceAdminReceiver.onProfileProvisioningComplete().
 */
public class FactoryResetProtectionReceiver extends BroadcastReceiver {
    
    private static final String TAG = "FRPReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "FRP broadcast received");
        setupFactoryResetProtection(context);
    }
    
    /**
     * Setup Factory Reset Protection
     * This is called when app becomes device owner
     */
    public static void setupFactoryResetProtection(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = com.riad.rrlkr.admin.EMIDeviceAdminReceiver.getComponentName(context);
        
        if (dpm == null) return;
        
        // Check if we are device owner
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.w(TAG, "Not device owner, cannot setup FRP");
            return;
        }
        
        try {
            // Store device fingerprint for later identification
            DeviceFingerprint fingerprint = new DeviceFingerprint(context);
            fingerprint.storeFingerprint();
            
            // Set Factory Reset Protection policy
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Set FRP account - device will require this account after factory reset
                // This helps us identify the device when it comes back online
                PersistableBundle frpData = new PersistableBundle();
                frpData.putString("persistent_device_id", fingerprint.getPersistentDeviceId());
                frpData.putString("imei", fingerprint.getIMEI());
                frpData.putString("serial", fingerprint.getSerialNumber());
                
                // Android 11+ (API 30): Set proper FRP policy
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        // CRITICAL: FRP MUST have at least one Google account email,
                        // otherwise the policy is effectively disabled and any user
                        // can sail through the post-reset setup wizard.
                        //
                        // The account is sourced from:
                        //   1. Admin-set value (PreferenceManager.setFRPAccount)
                        //   2. Falls back to BuildConfig.DEFAULT_FRP_ACCOUNT
                        //
                        // After factory reset, device shows "Verify your Google Account"
                        // and ONLY this account email can unlock setup.
                        com.riad.rrlkr.utils.PreferenceManager prefs =
                                com.riad.rrlkr.utils.PreferenceManager.getInstance(context);
                        String frpAccount = prefs.getFRPAccount();
                        if (frpAccount == null || frpAccount.isEmpty()) {
                            frpAccount = com.riad.rrlkr.BuildConfig.DEFAULT_FRP_ACCOUNT;
                            // Persist the default so DeviceProtectionManager can reuse it
                            prefs.setFRPAccount(frpAccount);
                        }

                        List<String> frpAccounts = new ArrayList<>();
                        frpAccounts.add(frpAccount);

                        FactoryResetProtectionPolicy frpPolicy =
                                new FactoryResetProtectionPolicy.Builder()
                                        .setFactoryResetProtectionAccounts(frpAccounts)
                                        .setFactoryResetProtectionEnabled(true)
                                        .build();
                        dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy);
                        Log.i(TAG, "FRP policy ENABLED with account: " + frpAccount
                                + " â€” factory reset will require this Google login");
                    } catch (Exception e) {
                        Log.w(TAG, "Could not set FRP policy (Android 11+): " + e.getMessage());
                    }
                }
            }
            
            // Disable factory reset for users
            // This prevents users from factory resetting without admin permission
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            
            // Apply ALL protections via DeviceProtectionManager
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
            protectionManager.applyAllProtections();
            
            Log.d(TAG, "Factory Reset Protection + All Protections configured");
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting up FRP", e);
        }
    }
}
