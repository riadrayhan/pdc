package com.riad.rrlkr.service;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.riad.rrlkr.BuildConfig;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.util.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * OTA Self-Update Service
 * 
 * Downloads and silently installs app updates using Device Owner PackageInstaller API.
 * No user interaction required â€” Device Owner privilege auto-approves the install.
 * 
 * This is the same approach used by commercial EMMs (SOTI, Hexnode, etc.)
 * for custom app distribution without AMAPI.
 */
public class AppSelfUpdateService {

    private static final String TAG = "AppSelfUpdate";
    private static final String APK_FILENAME = "emi_locker_update.apk";
    private static final String ACTION_INSTALL_COMPLETE = "com.riad.rrlkr.INSTALL_COMPLETE";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check_time";
    private static final long UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000; // 6 hours

    private final Context context;
    private final PreferenceManager preferenceManager;
    private BroadcastReceiver installReceiver;

    public AppSelfUpdateService(Context context) {
        this.context = context.getApplicationContext();
        this.preferenceManager = new PreferenceManager(context);
    }

    /**
     * Check server for available update then download & install if newer.
     * Safe to call from any thread â€” runs network on background executor.
     */
    public void checkAndUpdate() {
        checkAndUpdate(false);
    }

    /**
     * @param force If true, install even if same version
     */
    public void checkAndUpdate(boolean force) {
        new Thread(() -> {
            try {
                int currentVersion = getCurrentVersionCode();
                String serverUrl = BuildConfig.SERVER_URL;
                String checkUrl = serverUrl + "/app/check-update?current_version=" + currentVersion
                        + "&current_version_name=" + getCurrentVersionName();

                Log.i(TAG, "Checking for update: " + checkUrl);

                HttpURLConnection conn = (HttpURLConnection) new URL(checkUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Log.w(TAG, "Update check failed: HTTP " + responseCode);
                    return;
                }

                // Read response
                InputStream is = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
                is.close();
                conn.disconnect();

                String json = sb.toString();
                Log.d(TAG, "Update check response: " + json);

                // Parse JSON manually to avoid extra dependencies
                boolean hasUpdate = json.contains("\"has_update\": true") || json.contains("\"has_update\":true");
                boolean forceUpdate = json.contains("\"force_update\": true") || json.contains("\"force_update\":true");

                // Extract apk_url
                String apkUrl = extractJsonString(json, "apk_url");

                if (!hasUpdate && !force) {
                    Log.i(TAG, "No update available (current: " + currentVersion + ")");
                    preferenceManager.saveLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis());
                    return;
                }

                if (apkUrl == null || apkUrl.isEmpty()) {
                    Log.w(TAG, "No APK URL in update response");
                    return;
                }

                Log.i(TAG, "Update available! Downloading from: " + apkUrl);
                downloadAndInstall(apkUrl);

            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
            }
        }).start();
    }

    /**
     * Download APK from URL then silently install via PackageInstaller.
     * Called directly by FCM UPDATE_APP command handler.
     */
    public void downloadAndInstall(String apkUrl) {
        new Thread(() -> {
            File apkFile = null;
            try {
                apkFile = downloadApk(apkUrl);
                if (apkFile == null || !apkFile.exists()) {
                    Log.e(TAG, "APK download failed");
                    return;
                }

                // Verify APK is valid and signed with same key
                if (!verifyApk(apkFile)) {
                    Log.e(TAG, "APK verification failed â€” aborting install");
                    apkFile.delete();
                    return;
                }

                Log.i(TAG, "APK downloaded and verified. Starting silent install...");
                silentInstallApk(apkFile);

            } catch (Exception e) {
                Log.e(TAG, "Download and install failed", e);
                if (apkFile != null && apkFile.exists()) {
                    apkFile.delete();
                }
            }
        }).start();
    }

    /**
     * Download APK to internal cache directory.
     */
    private File downloadApk(String apkUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            Log.e(TAG, "APK download failed: HTTP " + responseCode);
            conn.disconnect();
            return null;
        }

        // Download to internal cache (no external storage permission needed)
        File cacheDir = new File(context.getCacheDir(), "updates");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File apkFile = new File(cacheDir, APK_FILENAME);

        InputStream in = conn.getInputStream();
        java.io.FileOutputStream out = new java.io.FileOutputStream(apkFile);

        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalBytes = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }

        out.flush();
        out.close();
        in.close();
        conn.disconnect();

        Log.i(TAG, "APK downloaded: " + totalBytes + " bytes to " + apkFile.getAbsolutePath());
        return apkFile;
    }

    /**
     * Verify APK is valid and signed with the same signing certificate as the current app.
     * Prevents installing tampered/different APKs.
     */
    private boolean verifyApk(File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();

            // Get current app's signing info
            PackageInfo currentInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentInfo = pm.getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES
                );
            } else {
                currentInfo = pm.getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNATURES
                );
            }

            // Get APK's signing info
            PackageInfo apkInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                apkInfo = pm.getPackageArchiveInfo(
                        apkFile.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES
                );
            } else {
                apkInfo = pm.getPackageArchiveInfo(
                        apkFile.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES
                );
            }

            if (apkInfo == null) {
                Log.e(TAG, "Cannot parse APK file");
                return false;
            }

            // Verify package name matches
            if (!context.getPackageName().equals(apkInfo.packageName)) {
                Log.e(TAG, "Package name mismatch: expected " + context.getPackageName()
                        + " got " + apkInfo.packageName);
                return false;
            }

            // Compare signing certificates
            byte[] currentSig = getFirstSignature(currentInfo);
            byte[] apkSig = getFirstSignature(apkInfo);

            if (currentSig == null || apkSig == null) {
                Log.e(TAG, "Could not extract signatures");
                return false;
            }

            if (!MessageDigest.isEqual(currentSig, apkSig)) {
                Log.e(TAG, "Signing certificate mismatch â€” possible tampering");
                return false;
            }

            Log.i(TAG, "APK verified: package=" + apkInfo.packageName
                    + " version=" + apkInfo.versionCode);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "APK verification error", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private byte[] getFirstSignature(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            android.content.pm.Signature[] sigs = info.signingInfo.getApkContentsSigners();
            if (sigs != null && sigs.length > 0) {
                return sigs[0].toByteArray();
            }
        }
        if (info.signatures != null && info.signatures.length > 0) {
            return info.signatures[0].toByteArray();
        }
        return null;
    }

    /**
     * Silent install using Device Owner PackageInstaller API.
     * Device Owner privilege auto-approves the install â€” no user prompt.
     */
    private void silentInstallApk(File apkFile) {
        try {
            // Verify we are Device Owner
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
                Log.w(TAG, "Not Device Owner â€” cannot do silent install. Falling back to manual.");
                fallbackInstall(apkFile);
                return;
            }

            PackageInstaller installer = context.getPackageManager().getPackageInstaller();

            // Create install session params
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
            );
            params.setAppPackageName(context.getPackageName());
            params.setSize(apkFile.length());

            // Device Owner can set installer package for trust
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            // Write APK to session
            OutputStream out = session.openWrite("emi_locker_update", 0, apkFile.length());
            FileInputStream in = new FileInputStream(apkFile);
            byte[] buffer = new byte[65536];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            session.fsync(out);
            out.close();
            in.close();

            Log.i(TAG, "APK written to install session. Committing...");

            // Register result receiver
            registerInstallReceiver(apkFile);

            // Create result intent
            Intent intent = new Intent(ACTION_INSTALL_COMPLETE);
            intent.setPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );

            // Commit â€” Device Owner auto-approves
            session.commit(pendingIntent.getIntentSender());
            Log.i(TAG, "Install session committed (sessionId=" + sessionId + ")");

        } catch (Exception e) {
            Log.e(TAG, "Silent install failed", e);
            fallbackInstall(apkFile);
        }
    }

    /**
     * Register broadcast receiver for install result.
     */
    private void registerInstallReceiver(File apkFile) {
        if (installReceiver != null) {
            try {
                context.unregisterReceiver(installReceiver);
            } catch (Exception ignored) {}
        }

        installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

                switch (status) {
                    case PackageInstaller.STATUS_SUCCESS:
                        Log.i(TAG, "OTA update installed successfully!");
                        preferenceManager.saveLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis());
                        // Clean up APK
                        if (apkFile.exists()) apkFile.delete();
                        break;

                    case PackageInstaller.STATUS_PENDING_USER_ACTION:
                        // Shouldn't happen for Device Owner, but handle gracefully
                        Log.w(TAG, "Install requires user action (unexpected for Device Owner)");
                        Intent confirmIntent = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(confirmIntent);
                        }
                        break;

                    case PackageInstaller.STATUS_FAILURE:
                    case PackageInstaller.STATUS_FAILURE_BLOCKED:
                    case PackageInstaller.STATUS_FAILURE_CONFLICT:
                    case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    case PackageInstaller.STATUS_FAILURE_INVALID:
                    case PackageInstaller.STATUS_FAILURE_STORAGE:
                        Log.e(TAG, "Install failed: status=" + status + " message=" + message);
                        if (apkFile.exists()) apkFile.delete();
                        break;

                    default:
                        Log.w(TAG, "Unknown install status: " + status);
                        break;
                }

                try {
                    ctx.unregisterReceiver(this);
                } catch (Exception ignored) {}
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL_COMPLETE));
        }
    }

    /**
     * Fallback: open standard install dialog (if not Device Owner).
     */
    private void fallbackInstall(File apkFile) {
        try {
            Log.w(TAG, "Using fallback (non-silent) install");
            android.net.Uri apkUri;
            Intent installIntent = new Intent(Intent.ACTION_VIEW);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", apkFile);
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = android.net.Uri.fromFile(apkFile);
            }

            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Fallback install failed", e);
        }
    }

    // ===== Utility methods =====

    /**
     * Whether enough time has passed to check for updates again.
     */
    public boolean shouldCheckForUpdate() {
        long lastCheck = preferenceManager.getLong(PREF_LAST_UPDATE_CHECK, 0);
        return (System.currentTimeMillis() - lastCheck) > UPDATE_CHECK_INTERVAL;
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            }
            return pInfo.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "0.0.0";
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * Simple JSON string extractor â€” avoids depending on org.json in service context.
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx == -1) return null;

        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;

        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }
}
