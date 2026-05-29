package com.riad.rrlkr.receiver;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receiver to clear Device Owner mode via ADB broadcast.
 * Usage: adb shell am broadcast -a com.riad.rrlkr.CLEAR_DEVICE_OWNER -n com.riad.rrlkr/.receiver.ClearDeviceOwnerReceiver
 */
public class ClearDeviceOwnerReceiver extends BroadcastReceiver {
    private static final String TAG = "ClearDeviceOwner";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Clear Device Owner request received");
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.clearDeviceOwnerApp(context.getPackageName());
                Log.i(TAG, "Device Owner cleared successfully!");
            } else {
                Log.w(TAG, "App is not device owner, nothing to clear");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear device owner", e);
        }
    }
}
