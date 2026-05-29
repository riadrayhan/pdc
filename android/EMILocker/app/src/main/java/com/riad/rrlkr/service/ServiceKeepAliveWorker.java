package com.riad.rrlkr.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.riad.rrlkr.util.PreferenceManager;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that ensures the DeviceMonitorService stays alive 24/7.
 * This is a fallback mechanism - if the foreground service is killed,
 * WorkManager will restart it periodically.
 * 
 * Also handles:
 * - Re-applying device protections
 * - Ensuring lock screen shows when device is locked
 * - Battery optimization bypass
 */
public class ServiceKeepAliveWorker extends Worker {

    private static final String TAG = "ServiceKeepAlive";
    private static final String WORK_NAME = "emi_locker_keep_alive";
    private static final long REPEAT_INTERVAL_MINUTES = 15; // Minimum is 15 min

    public ServiceKeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "KeepAlive worker running - ensuring service is alive");

        Context context = getApplicationContext();
        PreferenceManager prefs = new PreferenceManager(context);

        try {
            // 1. Restart the foreground service if it's not running
            ensureServiceRunning(context);

            // 2. Re-apply device protections (skip if admin disabled the app)
            if (prefs.isEnrolled() && !prefs.isAppDisabled()) {
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(context);
                if (protectionManager.isDeviceOwner()) {
                    protectionManager.blockUninstall();
                    protectionManager.disableUSBDebugging();
                    protectionManager.disableADB();
                    
                    // Ensure app hidden state matches preference
                    if (prefs.isAppHidden()) {
                        protectionManager.hideAppFromLauncher();
                    }
                }

                // 3. If device should be locked, ensure lock screen is showing
                if (prefs.isDeviceLocked()) {
                    LockManager.showLockScreen(context);
                }
            } else if (prefs.isAppDisabled()) {
                Log.d(TAG, "App is disabled by admin - skipping protection re-application");
            }

            Log.i(TAG, "KeepAlive worker completed successfully");
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "KeepAlive worker error", e);
            return Result.retry();
        }
    }

    /**
     * Ensure the DeviceMonitorService is running
     */
    private void ensureServiceRunning(Context context) {
        try {
            Intent serviceIntent = new Intent(context, DeviceMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "Service start intent sent");
        } catch (Exception e) {
            Log.e(TAG, "Error starting service from worker", e);
        }
    }

    /**
     * Schedule the periodic keep-alive worker.
     * Call this from Application.onCreate() and after boot.
     */
    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Run even without network
                    .setRequiresBatteryNotLow(false)  // Run even on low battery
                    .setRequiresCharging(false)        // Run even when not charging
                    .setRequiresStorageNotLow(false)   // Run even on low storage
                    .build();

            PeriodicWorkRequest keepAliveRequest =
                    new PeriodicWorkRequest.Builder(
                            ServiceKeepAliveWorker.class,
                            REPEAT_INTERVAL_MINUTES,
                            TimeUnit.MINUTES
                    )
                    .setConstraints(constraints)
                    .addTag("emi_locker_keepalive")
                    .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                    keepAliveRequest
            );

            Log.i(TAG, "KeepAlive worker scheduled (every " + REPEAT_INTERVAL_MINUTES + " min)");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling keep-alive worker", e);
        }
    }

    /**
     * Cancel the keep-alive worker (when unenrolling)
     */
    public static void cancel(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            Log.i(TAG, "KeepAlive worker cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling keep-alive worker", e);
        }
    }
}
