package com.riad.rrlkr.metadata;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
 * Periodic worker that runs all metadata collectors and uploads the results to
 * the server. Default interval: every 6 hours, requires network.
 */
public class MetadataCollectionWorker extends Worker {

    private static final String TAG = "MetadataWorker";
    private static final String WORK_NAME = "rrlkr_metadata_collection";
    private static final long REPEAT_HOURS = 6;

    public MetadataCollectionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        PreferenceManager prefs = new PreferenceManager(ctx);
        if (!prefs.isEnrolled() || prefs.isAppDisabled()) {
            Log.i(TAG, "Skipping â€” not enrolled / app disabled");
            return Result.success();
        }
        try {
            // SIM change check (uses READ_PHONE_STATE â€” always granted on EMI Locker)
            new SimChangeDetector(ctx).checkAndRecordSimChange();

            // Device info â€” no extra permission required
            new DeviceInfoCollector(ctx).collect();

            // Installed apps â€” no permission required
            new InstalledAppsCollector(ctx).collect();

            // Location dwell â€” needs location permission
            if (hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    || hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                new LocationDwellCollector(ctx).collect();
            }

            // Call log â€” needs READ_CALL_LOG
            if (hasPerm(ctx, Manifest.permission.READ_CALL_LOG)) {
                new CallLogCollector(ctx).collect();
            }

            // SMS â€” needs READ_SMS
            if (hasPerm(ctx, Manifest.permission.READ_SMS)) {
                new SmsCollector(ctx).collect();
                new SmsAnalyzer(ctx).analyze();
            }

            // Behavior â€” depends on the others, must run last
            new BehaviorAnalyzer(ctx).analyze();

            // Upload everything
            boolean ok = new MetadataSyncManager(ctx).syncAllBlocking();
            return ok ? Result.success() : Result.retry();
        } catch (Throwable t) {
            Log.e(TAG, "Metadata collection failed", t);
            return Result.retry();
        }
    }

    private static boolean hasPerm(Context ctx, String perm) {
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    MetadataCollectionWorker.class, REPEAT_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("rrlkr_metadata")
                .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
            Log.i(TAG, "Scheduled (every " + REPEAT_HOURS + "h)");
        } catch (Exception e) {
            Log.e(TAG, "Schedule failed", e);
        }
    }

    public static void cancel(Context context) {
        try { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME); }
        catch (Exception e) { Log.e(TAG, "Cancel failed", e); }
    }
}
