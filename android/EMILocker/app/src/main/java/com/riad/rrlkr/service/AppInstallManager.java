package com.riad.rrlkr.service;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.riad.rrlkr.utils.PreferenceManager;

import java.io.File;

/**
 * Handles automatic app installation
 * Used when device comes back online after factory reset
 */
public class AppInstallManager {
    
    private static final String TAG = "AppInstallManager";
    private static final String APK_FILENAME = "emi_locker.apk";
    
    private final Context context;
    private final PreferenceManager preferenceManager;
    private long downloadId = -1;
    
    public AppInstallManager(Context context) {
        this.context = context;
        this.preferenceManager = PreferenceManager.getInstance(context);
    }
    
    /**
     * Download and install the app from server
     * This is triggered when server identifies a known device
     */
    public void downloadAndInstall(String apkUrl) {
        Log.d(TAG, "Starting APK download from: " + apkUrl);
        
        try {
            // Create download request
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("RR Device Manager Update");
            request.setDescription("Downloading required security app...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            
            // Set destination
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME);
            
            // Allow download over mobile data
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            
            // Start download
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadId = downloadManager.enqueue(request);
                
                // Store download ID
                preferenceManager.saveLong("pending_download_id", downloadId);
                
                // Register receiver for download complete
                registerDownloadReceiver();
                
                Log.d(TAG, "Download started with ID: " + downloadId);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error downloading APK", e);
        }
    }
    
    /**
     * Register broadcast receiver for download completion
     */
    private void registerDownloadReceiver() {
        BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    Log.d(TAG, "Download complete");
                    installApk();
                    ctx.unregisterReceiver(this);
                }
            }
        };
        
        context.registerReceiver(downloadReceiver, 
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED);
    }
    
    /**
     * Install the downloaded APK
     */
    public void installApk() {
        try {
            File apkFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME);
            
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found");
                return;
            }
            
            Uri apkUri;
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7.0+, use FileProvider
                apkUri = FileProvider.getUriForFile(context, 
                    context.getPackageName() + ".fileprovider", apkFile);
                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            context.startActivity(installIntent);
            
            Log.d(TAG, "Install intent started");
            
        } catch (Exception e) {
            Log.e(TAG, "Error installing APK", e);
        }
    }
    
    /**
     * Silent install using Device Owner permission
     * This works without user interaction if app is device owner
     */
    public void silentInstall(String apkPath) {
        // Note: Silent install requires device owner or system app permissions
        // This is handled by DevicePolicyManager.installPackage() in newer APIs
        Log.d(TAG, "Silent install not available - falling back to regular install");
        installApk();
    }
    
    /**
     * Check for pending installation
     */
    public void checkPendingInstall() {
        long pendingId = preferenceManager.getLong("pending_download_id", -1);
        if (pendingId != -1) {
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(pendingId);
                
                Cursor cursor = downloadManager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApk();
                    }
                    cursor.close();
                }
            }
        }
    }
}
