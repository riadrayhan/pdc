package com.riad.rrlkr.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.util.PreferenceManager;

/**
 * USB Monitor Receiver
 * Detects USB connections and immediately re-applies USB data blocking.
 * Forces charge-only mode every time USB is connected.
 */
public class USBMonitorReceiver extends BroadcastReceiver {

    private static final String TAG = "USBMonitorReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "USB event received: " + action);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action) ||
            Intent.ACTION_POWER_CONNECTED.equals(action)) {
            
            PreferenceManager prefs = new PreferenceManager(context);
            
            if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                Log.i(TAG, "USB connected on enrolled device - ENFORCING charge-only mode");
                
                // Aggressively re-apply ALL USB protections
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                protectionManager.disableUSBDebugging();
                protectionManager.disableUSBFileTransfer();
                protectionManager.disableADB();
                
                // Permanently disable USB data (most important)
                protectionManager.disableUSBDataPermanently();
                
                Log.i(TAG, "USB data BLOCKED - charging only");
            } else if (prefs.isAppDisabled()) {
                Log.d(TAG, "App disabled by admin - allowing USB connection");
            }
        }
    }
}
