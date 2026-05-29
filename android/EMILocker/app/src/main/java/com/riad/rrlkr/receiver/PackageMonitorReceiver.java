package com.riad.rrlkr.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * Package Monitor Receiver
 * Monitors for attempts to uninstall the device management app
 * Re-applies uninstall block if someone tries to remove the app
 */
public class PackageMonitorReceiver extends BroadcastReceiver {

    private static final String TAG = "PackageMonitor";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (action == null) return;
        
        Log.i(TAG, "Package event: " + action);
        
        String packageName = null;
        if (intent.getData() != null) {
            packageName = intent.getData().getSchemeSpecificPart();
        }

        switch (action) {
            case Intent.ACTION_PACKAGE_REMOVED:
            case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                if (context.getPackageName().equals(packageName)) {
                    Log.w(TAG, "RR Device Manager package removal detected!");
                    // This receiver won't work after our own package is removed,
                    // but this handles the edge case where it might be called
                }
                break;

            case Intent.ACTION_PACKAGE_CHANGED:
            case Intent.ACTION_PACKAGE_REPLACED:
                if (context.getPackageName().equals(packageName)) {
                    Log.i(TAG, "Package changed/replaced - re-applying protections");
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                    protectionManager.applyAllProtections();
                }
                break;

            case Intent.ACTION_PACKAGE_ADDED:
                // A new package was installed - check if we should monitor it
                Log.d(TAG, "New package installed: " + packageName);
                break;

            case "android.intent.action.QUERY_PACKAGE_RESTART":
            case Intent.ACTION_PACKAGE_RESTARTED:
                if (context.getPackageName().equals(packageName)) {
                    Log.w(TAG, "Force stop detected - restarting");
                    // Re-apply protections
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                    protectionManager.blockUninstall();
                }
                break;
        }
    }
}
