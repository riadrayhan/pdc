package com.riad.rrlkr.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.riad.rrlkr.service.DeviceReEnrollService;
import com.riad.rrlkr.utils.PreferenceManager;

/**
 * Receives network connectivity changes
 * Triggers re-enrollment check when device comes online
 */
public class NetworkChangeReceiver extends BroadcastReceiver {
    
    private static final String TAG = "NetworkChangeReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            if (isNetworkAvailable(context)) {
                Log.d(TAG, "Network available - checking device status");
                
                // Start re-enrollment check service
                Intent serviceIntent = new Intent(context, DeviceReEnrollService.class);
                serviceIntent.setAction("CHECK_ENROLLMENT");
                context.startService(serviceIntent);
            }
        }
    }
    
    private boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }
}
