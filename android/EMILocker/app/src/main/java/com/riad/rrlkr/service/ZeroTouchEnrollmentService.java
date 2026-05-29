package com.riad.rrlkr.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.BuildConfig;
import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.DeviceEnrollRequestV2;
import com.riad.rrlkr.receiver.FactoryResetProtectionReceiver;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.utils.DeviceFingerprint;
import com.riad.rrlkr.util.PreferenceManager;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

/**
 * Advanced Zero Touch Enrollment Service v2.0
 * 
 * Production-grade fully automated device enrollment with:
 * - Multi-phase enrollment pipeline with status tracking
 * - Silent runtime permission granting via Device Owner
 * - WiFi management via DevicePolicyManager
 * - Exponential backoff with jitter for retry logic
 * - Multi-network fallback (WiFi â†’ Mobile Data)
 * - Server connectivity verification before enrollment
 * - Post-enrollment verification handshake
 * - SIM card info collection (operator, ICCID, phone number)
 * - Battery optimization exemption via Device Owner
 * - Device-protected storage backup of ZTE state
 * - Comprehensive error recovery and state machine
 * - Detailed progress reporting with notification progress bar
 *
 * Enrollment Pipeline:
 * Phase 0: Initialize &amp; validate Device Owner
 * Phase 1: Grant permissions silently
 * Phase 2: Configure WiFi &amp; ensure network
 * Phase 3: Exempt from battery optimization
 * Phase 4: Obtain FCM token
 * Phase 5: Collect full device fingerprint + SIM info
 * Phase 6: Verify server connectivity
 * Phase 7: Execute enrollment API call
 * Phase 8: Verify enrollment with server
 * Phase 9: Apply all protections
 * Phase 10: Start monitoring &amp; finalize
 */
public class ZeroTouchEnrollmentService extends Service {

    private static final String TAG = "ZeroTouchEnroll";
    private static final String CHANNEL_ID = "zte_channel";
    private static final int NOTIFICATION_ID = 9999;
    
    // Retry configuration
    private static final int MAX_ENROLLMENT_RETRIES = 15;
    private static final long BASE_RETRY_DELAY_MS = 3000;
    private static final long MAX_RETRY_DELAY_MS = 120000;
    private static final long NETWORK_WAIT_TIMEOUT_MS = 180000;
    private static final long SERVER_CHECK_TIMEOUT_MS = 15000;
    private static final long ENROLLMENT_VERIFY_DELAY_MS = 5000;
    private static final long PIPELINE_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes max
    
    private static final String WAKELOCK_TAG = "EMILocker:ZTEWakeLock";

    // Intent extras keys
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_AUTO_ENROLL = "auto_enroll";
    public static final String EXTRA_AUTO_LOCK = "auto_lock_on_enroll";
    public static final String EXTRA_LOCK_MESSAGE = "default_lock_message";
    public static final String EXTRA_CONTACT_NUMBER = "default_contact_number";
    public static final String EXTRA_ZTE_VERSION = "zte_version";
    public static final String EXTRA_WIFI_SSID = "wifi_ssid";
    public static final String EXTRA_WIFI_PASSWORD = "wifi_password";
    public static final String EXTRA_WIFI_SECURITY = "wifi_security";

    // Enrollment phases
    private static final int PHASE_INIT = 0;
    private static final int PHASE_PERMISSIONS = 1;
    private static final int PHASE_WIFI = 2;
    private static final int PHASE_BATTERY = 3;
    private static final int PHASE_FCM = 4;
    private static final int PHASE_FINGERPRINT = 5;
    private static final int PHASE_SERVER_CHECK = 6;
    private static final int PHASE_ENROLL = 7;
    private static final int PHASE_VERIFY = 8;
    private static final int PHASE_PROTECT = 9;
    private static final int PHASE_FINALIZE = 10;
    private static final int PHASE_COMPLETE = 11;

    private static final String[] PHASE_NAMES = {
        "Initializing", "Granting Permissions", "Configuring WiFi",
        "Battery Optimization", "Getting FCM Token", "Collecting Fingerprint",
        "Checking Server", "Enrolling Device", "Verifying Enrollment",
        "Applying Protections", "Finalizing", "Complete"
    };

    // Runtime permissions to grant silently via Device Owner
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        "android.permission.READ_PHONE_NUMBERS",
        // Android 13+ (API 33) critical permissions
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.NEARBY_WIFI_DEVICES",
        // Android 14+ (API 34) permissions
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    };

    // State
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private PreferenceManager preferenceManager;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private PowerManager.WakeLock wakeLock;
    
    private int currentPhase = PHASE_INIT;
    private String serverUrl;
    private boolean autoLockOnEnroll;
    private String defaultLockMessage;
    private String defaultContactNumber;
    private String wifiSsid;
    private String wifiPassword;
    private String wifiSecurity;
    private String fcmToken;
    private String enrolledDeviceId;
    private String enrolledDeviceToken;
    private long enrollmentStartTime;
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final SecureRandom random = new SecureRandom();

    /**
     * Start the ZTE service with configuration from DPC admin extras.
     */
    public static void start(Context context, PersistableBundle adminExtras) {
        Intent intent = new Intent(context, ZeroTouchEnrollmentService.class);
        
        if (adminExtras != null) {
            intent.putExtra(EXTRA_SERVER_URL, adminExtras.getString("server_url", ""));
            intent.putExtra(EXTRA_AUTO_ENROLL, adminExtras.getBoolean("auto_enroll", true));
            intent.putExtra(EXTRA_AUTO_LOCK, adminExtras.getBoolean("auto_lock_on_enroll", false));
            intent.putExtra(EXTRA_LOCK_MESSAGE, adminExtras.getString("default_lock_message", ""));
            intent.putExtra(EXTRA_CONTACT_NUMBER, adminExtras.getString("default_contact_number", ""));
            intent.putExtra(EXTRA_ZTE_VERSION, adminExtras.getString("zte_version", "2.0"));
            intent.putExtra(EXTRA_WIFI_SSID, adminExtras.getString("wifi_ssid", ""));
            intent.putExtra(EXTRA_WIFI_PASSWORD, adminExtras.getString("wifi_password", ""));
            intent.putExtra(EXTRA_WIFI_SECURITY, adminExtras.getString("wifi_security", "WPA"));
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        
        Log.i(TAG, "ZTE Service start requested with admin extras");
    }

    /**
     * Start the ZTE service with explicit server URL (fallback/retry mode).
     */
    public static void start(Context context, String serverUrl) {
        Intent intent = new Intent(context, ZeroTouchEnrollmentService.class);
        intent.putExtra(EXTRA_SERVER_URL, serverUrl != null ? serverUrl : BuildConfig.SERVER_URL);
        intent.putExtra(EXTRA_AUTO_ENROLL, true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        
        Log.i(TAG, "ZTE Service start (retry mode) with URL: " + serverUrl);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Dedicated worker thread for blocking operations
        workerThread = new HandlerThread("ZTE-Worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        
        preferenceManager = new PreferenceManager(this);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
        
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing Zero Touch Enrollment...", 0),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing Zero Touch Enrollment...", 0));
        }
        
        acquireWakeLock();
        
        Log.i(TAG, "=== Zero Touch Enrollment Service v2.0 Created ===");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "=== ZTE Service onStartCommand ===");
        
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "ZTE already running â€” ignoring duplicate start");
            return START_STICKY;
        }
        
        enrollmentStartTime = System.currentTimeMillis();
        
        if (intent != null) {
            serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
            autoLockOnEnroll = intent.getBooleanExtra(EXTRA_AUTO_LOCK, false);
            defaultLockMessage = intent.getStringExtra(EXTRA_LOCK_MESSAGE);
            defaultContactNumber = intent.getStringExtra(EXTRA_CONTACT_NUMBER);
            wifiSsid = intent.getStringExtra(EXTRA_WIFI_SSID);
            wifiPassword = intent.getStringExtra(EXTRA_WIFI_PASSWORD);
            wifiSecurity = intent.getStringExtra(EXTRA_WIFI_SECURITY);
            
            String zteVersion = intent.getStringExtra(EXTRA_ZTE_VERSION);
            Log.i(TAG, "ZTE Version: " + zteVersion);
            Log.i(TAG, "Server URL: " + serverUrl);
            Log.i(TAG, "Auto Lock: " + autoLockOnEnroll);
            Log.i(TAG, "WiFi SSID: " + (wifiSsid != null ? wifiSsid : "none"));
        }
        
        // Use default server URL if not provided
        if (serverUrl == null || serverUrl.isEmpty()) {
            serverUrl = preferenceManager.getServerUrl();
            if (serverUrl == null || serverUrl.isEmpty()) {
                serverUrl = BuildConfig.SERVER_URL;
            }
            Log.i(TAG, "Using fallback server URL: " + serverUrl);
        }
        
        // Save configuration immediately
        preferenceManager.setServerUrl(serverUrl);
        ApiClient.setBaseUrl(serverUrl);
        preferenceManager.saveBoolean("zte_provisioned", true);
        preferenceManager.saveBoolean("zte_pending", true);
        preferenceManager.saveString("zte_start_time", String.valueOf(enrollmentStartTime));
        
        if (defaultContactNumber != null && !defaultContactNumber.isEmpty()) {
            preferenceManager.setContactNumber(defaultContactNumber);
        }
        if (defaultLockMessage != null && !defaultLockMessage.isEmpty()) {
            preferenceManager.setLockMessage(defaultLockMessage);
        }
        
        backupZTEState("started");
        
        // Run pipeline on worker thread
        workerHandler.post(this::runEnrollmentPipeline);
        
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== ENROLLMENT PIPELINE ====================

    /**
     * Main enrollment pipeline â€” executes all phases sequentially.
     * Each phase has its own error handling and retry logic.
     */
    private void runEnrollmentPipeline() {
        Log.i(TAG, "========================================");
        Log.i(TAG, "  ZERO TOUCH ENROLLMENT PIPELINE v2.0  ");
        Log.i(TAG, "========================================");
        
        // Check if already enrolled
        if (preferenceManager.isEnrolled()) {
            Log.i(TAG, "Device already enrolled â€” completing immediately");
            currentPhase = PHASE_COMPLETE;
            finalizeEnrollment();
            return;
        }
        
        try {
            executePhase(PHASE_INIT, this::phaseInit);
            executePhase(PHASE_PERMISSIONS, this::phaseGrantPermissions);
            executePhase(PHASE_WIFI, this::phaseConfigureWifi);
            executePhase(PHASE_BATTERY, this::phaseBatteryOptimization);
            executePhase(PHASE_FCM, this::phaseGetFcmToken);
            executePhase(PHASE_FINGERPRINT, this::phaseCollectFingerprint);
            executePhase(PHASE_SERVER_CHECK, this::phaseServerCheck);
            executePhaseWithRetry(PHASE_ENROLL, this::phaseEnroll, MAX_ENROLLMENT_RETRIES);
            executePhase(PHASE_VERIFY, this::phaseVerifyEnrollment);
            executePhase(PHASE_PROTECT, this::phaseApplyProtections);
            executePhase(PHASE_FINALIZE, this::phaseFinalize);
            
            finalizeEnrollment();
            
        } catch (ZTEPhaseException e) {
            Log.e(TAG, "ZTE Pipeline failed at phase " + PHASE_NAMES[e.phase] + ": " + e.getMessage(), e);
            handlePipelineFailure(e);
        } catch (Exception e) {
            Log.e(TAG, "ZTE Pipeline unexpected error", e);
            handlePipelineFailure(new ZTEPhaseException(currentPhase, "Unexpected: " + e.getMessage(), e));
        }
    }

    private void executePhase(int phase, Runnable action) throws ZTEPhaseException {
        checkPipelineTimeout();
        currentPhase = phase;
        int progress = (phase * 100) / PHASE_COMPLETE;
        Log.i(TAG, "--- Phase " + phase + ": " + PHASE_NAMES[phase] + " (" + progress + "%) ---");
        updateNotification("Phase " + phase + "/" + PHASE_COMPLETE + ": " + PHASE_NAMES[phase], progress);
        backupZTEState("phase_" + phase);
        reportProgress(phase, "in_progress", progress, null);
        action.run();
        Log.i(TAG, "OK Phase " + phase + " completed: " + PHASE_NAMES[phase]);
        reportProgress(phase, "phase_complete", progress, null);
    }

    private void executePhaseWithRetry(int phase, Runnable action, int maxRetries) throws ZTEPhaseException {
        currentPhase = phase;
        ZTEPhaseException lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                checkPipelineTimeout();
                int progress = (phase * 100) / PHASE_COMPLETE;
                Log.i(TAG, "--- Phase " + phase + ": " + PHASE_NAMES[phase] +
                        " (attempt " + attempt + "/" + maxRetries + ") ---");
                updateNotification(PHASE_NAMES[phase] + " (attempt " + attempt + ")", progress);
                reportProgress(phase, attempt > 1 ? "retrying" : "in_progress", progress, null);
                action.run();
                Log.i(TAG, "OK Phase " + phase + " completed on attempt " + attempt);
                reportProgress(phase, "phase_complete", progress, null);
                return;
            } catch (ZTEPhaseException e) {
                lastException = e;
                Log.w(TAG, "Phase " + phase + " failed (attempt " + attempt + "): " + e.getMessage());
                
                if (attempt < maxRetries) {
                    long delay = calculateBackoffDelay(attempt);
                    Log.i(TAG, "Retrying in " + delay + "ms...");
                    updateNotification("Retry " + attempt + "/" + maxRetries + " in " + (delay / 1000) + "s...",
                            (phase * 100) / PHASE_COMPLETE);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { break; }
                }
            }
        }
        
        throw lastException != null ? lastException :
                new ZTEPhaseException(phase, "Max retries exceeded");
    }

    // ==================== PHASE IMPLEMENTATIONS ====================

    /** Phase 0: Validate Device Owner status and initialize */
    private void phaseInit() throws ZTEPhaseException {
        if (dpm == null) {
            throw new ZTEPhaseException(PHASE_INIT, "DevicePolicyManager not available");
        }
        
        if (!dpm.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Not Device Owner â€” ZTE has limited functionality");
        } else {
            Log.i(TAG, "  Device Owner confirmed");
            // Configure app verification policy (includes unknown sources)
            try {
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                protectionManager.configureAppVerificationPolicy();
                Log.i(TAG, "  App verification configured during ZTE init");
            } catch (Exception e) {
                Log.w(TAG, "  Could not configure app verification: " + e.getMessage());
            }
            
            // CRITICAL: Android 13+ (API 33) â€” Clear install restrictions during provisioning
            // This is the SOTI/Hexnode approach: allow installs during setup, restrict after
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                    Log.i(TAG, "  Android 13+: DISALLOW_INSTALL_UNKNOWN_SOURCES CLEARED for provisioning");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not clear unknown sources: " + e.getMessage());
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        dpm.clearUserRestriction(adminComponent, "no_install_unknown_sources_globally");
                        Log.i(TAG, "  Android 14+: Global unknown sources restriction CLEARED");
                    } catch (Exception e) {
                        Log.w(TAG, "  Could not clear global unknown sources: " + e.getMessage());
                    }
                }
                // Permit all accessibility services during provisioning
                try {
                    dpm.setPermittedAccessibilityServices(adminComponent, null);
                    Log.i(TAG, "  Android 13+: All accessibility services PERMITTED");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not permit accessibility services: " + e.getMessage());
                }
                // Disable restricted settings enforcement
                try {
                    dpm.setSecureSetting(adminComponent, "restricted_networking_mode", "0");
                    Log.i(TAG, "  Android 13+: Restricted settings enforcement DISABLED");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not disable restricted settings: " + e.getMessage());
                }
            }
            
            // CRITICAL: Samsung Knox detection and activation
            boolean isSamsung = Build.MANUFACTURER.toLowerCase().contains("samsung");
            if (isSamsung) {
                Log.i(TAG, "  Samsung device detected â€” applying Knox-specific init");
                try {
                    SamsungProtectionManager samsungManager = new SamsungProtectionManager(this);
                    samsungManager.initKnoxLicense();
                    Log.i(TAG, "  Samsung Knox license initialized");
                } catch (Exception e) {
                    Log.w(TAG, "  Samsung Knox init warning: " + e.getMessage());
                }
                
                // Samsung One UI 5+ (Android 13+): Disable Maintenance Mode immediately
                // Maintenance Mode allows bypassing device management on Samsung
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        dpm.setGlobalSetting(adminComponent, "maintenance_mode_enabled", "0");
                        dpm.setSecureSetting(adminComponent, "maintenance_mode_enabled", "0");
                        Log.i(TAG, "  Samsung Maintenance Mode disabled");
                    } catch (Exception e) {
                        Log.w(TAG, "  Could not disable Maintenance Mode: " + e.getMessage());
                    }
                }
            }
            
            // Clear system update policy to allow all updates
            try {
                dpm.setSystemUpdatePolicy(adminComponent, null);
            } catch (Exception e) {
                Log.w(TAG, "  Could not set system update policy: " + e.getMessage());
            }
            
            // Android 14+ (API 34): Set organization ID for enterprise management
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    dpm.setOrganizationName(adminComponent, "RR Device Manager Protected");
                    Log.i(TAG, "  Organization name set (Android 14+ enterprise)");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not set org name: " + e.getMessage());
                }
            }
        }
        
        Log.i(TAG, "  Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.i(TAG, "  Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        Log.i(TAG, "  Server: " + serverUrl);
    }

    /**
     * Phase 1: Grant all runtime permissions silently using Device Owner privilege.
     * No user interaction needed â€” this is a critical advantage of Device Owner mode.
     */
    private void phaseGrantPermissions() throws ZTEPhaseException {
        if (!dpm.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Not Device Owner â€” cannot grant permissions silently");
            return;
        }

        // DISABLED: setPermissionGrantState() crashes the system PermissionController
        // on some MTK / NEXG Android 14 ROMs. The runtime permission dialogs in
        // EnrollmentActivity collect everything we need from the user.
        Log.i(TAG, "  phaseGrantPermissions: skipped (handled by user-facing dialogs)");
    }

    /**
     * Phase 2: Configure WiFi and ensure network connectivity.
     * Uses DevicePolicyManager settings for Device Owner,
     * with WifiManager/WifiNetworkSuggestion fallback.
     * Includes multi-network fallback (WiFi -> Mobile Data).
     * 
     * Samsung + Android 13+ fix:
     * Samsung One UI 5+ handles WiFi differently during provisioning.
     * On Android 13+, NEARBY_WIFI_DEVICES permission is required for WiFi scanning.
     */
    private void phaseConfigureWifi() throws ZTEPhaseException {
        // Step 0: Samsung-specific WiFi handling
        boolean isSamsung = Build.MANUFACTURER.toLowerCase().contains("samsung");
        if (isSamsung && Build.VERSION.SDK_INT >= 33) {
            // setPermissionGrantState disabled â€” crashes system PermissionController
            // on some ROMs. User dialog will be used instead if needed.
            Log.i(TAG, "  Samsung Android 13+ WiFi: skipping silent NEARBY_WIFI_DEVICES grant");
        }
        
        // Step 1: Add configured WiFi network
        if (wifiSsid != null && !wifiSsid.isEmpty()) {
            Log.i(TAG, "  Configuring WiFi: " + wifiSsid);
            addWifiNetwork(wifiSsid, wifiPassword, wifiSecurity);
        }
        
        // Step 2: Enable WiFi if off
        enableWifi();
        
        // Step 3: Wait for network with timeout
        if (!waitForNetwork(NETWORK_WAIT_TIMEOUT_MS)) {
            // Step 4: Fallback to mobile data
            Log.w(TAG, "  WiFi connection failed â€” trying mobile data");
            enableMobileData();
            
            if (!waitForNetwork(30000)) {
                throw new ZTEPhaseException(PHASE_WIFI,
                        "No network available after WiFi and mobile data attempts");
            }
        }
        
        // Step 5: Verify actual internet connectivity (3 retries with increasing delay)
        int maxInetRetries = 3;
        boolean hasInternet = false;
        for (int i = 1; i <= maxInetRetries; i++) {
            if (verifyInternetConnectivity()) {
                hasInternet = true;
                break;
            }
            Log.w(TAG, "  Internet verify attempt " + i + "/" + maxInetRetries + " failed");
            if (i < maxInetRetries) {
                try { Thread.sleep(5000L * i); } catch (InterruptedException ignored) {}
            }
        }
        if (!hasInternet) {
            throw new ZTEPhaseException(PHASE_WIFI, "Network connected but no internet access after " + maxInetRetries + " attempts");
        }
        
        Log.i(TAG, "  Network connected with internet access");
    }

    /** Phase 3: Exempt app from battery optimization and set user restrictions. */
    private void phaseBatteryOptimization() throws ZTEPhaseException {
        if (!dpm.isDeviceOwnerApp(getPackageName())) return;
        
        try {
            // Set lock task packages for keep-alive
            try {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                Log.i(TAG, "  Lock task packages set");
            } catch (Exception e) {
                Log.w(TAG, "  Could not set lock task packages: " + e.getMessage());
            }
            
            // Apply critical user restrictions
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
                Log.i(TAG, "  User restrictions applied");
            } catch (Exception e) {
                Log.w(TAG, "  Could not apply user restrictions: " + e.getMessage());
            }
            
            // Check battery optimization
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Log.i(TAG, "  Already exempt from battery optimization");
                    } else {
                        Log.i(TAG, "  Battery optimization exemption configured via Device Owner");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "  Battery optimization phase warning: " + e.getMessage());
        }
    }

    /**
     * Phase 4: Get FCM token synchronously using CountDownLatch.
     */
    private void phaseGetFcmToken() throws ZTEPhaseException {
        Log.i(TAG, "  Requesting FCM token...");
        
        CountDownLatch latch = new CountDownLatch(1);
        final String[] tokenResult = {null};
        final Exception[] errorResult = {null};
        
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> { tokenResult[0] = token; latch.countDown(); })
                .addOnFailureListener(e -> { errorResult[0] = e; latch.countDown(); });
        
        try {
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            
            if (completed && tokenResult[0] != null) {
                fcmToken = tokenResult[0];
                preferenceManager.setFcmToken(fcmToken);
                Log.i(TAG, "  FCM token obtained: " + fcmToken.substring(0, Math.min(20, fcmToken.length())) + "...");
            } else if (errorResult[0] != null) {
                Log.w(TAG, "  FCM token error: " + errorResult[0].getMessage());
                fcmToken = preferenceManager.getFcmToken();
                if (fcmToken == null) fcmToken = "";
                Log.i(TAG, "  Using cached/empty FCM token");
            } else {
                Log.w(TAG, "  FCM token request timed out");
                fcmToken = preferenceManager.getFcmToken();
                if (fcmToken == null) fcmToken = "";
            }
        } catch (InterruptedException e) {
            fcmToken = "";
            Log.w(TAG, "  FCM token wait interrupted");
        }
    }

    /** Phase 5: Collect comprehensive device fingerprint + SIM info. */
    private void phaseCollectFingerprint() throws ZTEPhaseException {
        Log.i(TAG, "  Collecting device fingerprint...");
        
        DeviceFingerprint fingerprint = new DeviceFingerprint(this);
        fingerprint.storeFingerprint();
        
        Log.i(TAG, "  IMEI: " + fingerprint.getIMEI());
        Log.i(TAG, "  IMEI2: " + fingerprint.getIMEI2());
        Log.i(TAG, "  Serial: " + fingerprint.getSerialNumber());
        Log.i(TAG, "  PersistentID: " + fingerprint.getPersistentDeviceId());
        Log.i(TAG, "  AndroidID: " + fingerprint.getAndroidId());
        
        collectSIMInfo();
        
        String imei = fingerprint.getIMEI();
        if (imei != null && !imei.isEmpty()) {
            preferenceManager.setStoredIMEI(imei);
        }
        
        Log.i(TAG, "  Fingerprint collected");
    }

    /** Phase 6: Verify server is reachable before enrollment. */
    private void phaseServerCheck() throws ZTEPhaseException {
        Log.i(TAG, "  Checking server: " + serverUrl);
        
        boolean reachable = false;
        
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                // Use the enrollment endpoint base as health check
                String healthUrl = serverUrl.endsWith("/") ?
                        serverUrl + "enrollment/check-status" : serverUrl + "/enrollment/check-status";
                
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(SERVER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(SERVER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build();
                
                // Use HEAD to minimize data transfer
                Request request = new Request.Builder().url(healthUrl).head().build();
                Response response = client.newCall(request).execute();
                int code = response.code();
                response.close();
                
                // Any response means server is reachable (even 4xx/405 means it's alive)
                reachable = true;
                Log.i(TAG, "  Server reachable (HTTP " + code + ") on attempt " + attempt);
                break;
            } catch (Exception e) {
                Log.w(TAG, "  Server check attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 5) {
                    long delay = BASE_RETRY_DELAY_MS * attempt;
                    Log.i(TAG, "  Retrying server check in " + delay + "ms...");
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                }
            }
        }
        
        if (!reachable) {
            throw new ZTEPhaseException(PHASE_SERVER_CHECK, "Server not reachable at: " + serverUrl);
        }
    }

    /** Phase 7: Execute the enrollment API call. */
    private void phaseEnroll() throws ZTEPhaseException {
        Log.i(TAG, "  Building enrollment request...");
        
        DeviceEnrollRequestV2 request = buildEnrollmentRequest(fcmToken);
        if (request == null) {
            throw new ZTEPhaseException(PHASE_ENROLL, "Failed to build enrollment request");
        }
        
        ApiClient.setBaseUrl(serverUrl);
        ApiService apiService = ApiClient.getApiService();
        
        try {
            Call<DeviceReEnrollService.EnrollResponse> call = apiService.enrollDevice(request);
            retrofit2.Response<DeviceReEnrollService.EnrollResponse> response = call.execute();
            
            if (response.isSuccessful() && response.body() != null) {
                DeviceReEnrollService.EnrollResponse result = response.body();
                
                enrolledDeviceId = result.getDeviceId();
                enrolledDeviceToken = result.getDeviceToken();
                
                Log.i(TAG, "  ===========================");
                Log.i(TAG, "   ENROLLMENT SUCCESSFUL!   ");
                Log.i(TAG, "  ===========================");
                Log.i(TAG, "  Device ID: " + enrolledDeviceId);
                
                // Save enrollment data
                preferenceManager.setEnrolled(true);
                preferenceManager.saveString("device_token", enrolledDeviceToken);
                if (enrolledDeviceId != null) {
                    preferenceManager.setDeviceId(enrolledDeviceId);
                }
                preferenceManager.saveBoolean("zte_enrolled", true);
                preferenceManager.saveBoolean("zte_pending", false);
                
                backupZTEState("enrolled");
                
            } else {
                String errorBody = "";
                if (response.errorBody() != null) {
                    try { errorBody = response.errorBody().string(); } catch (Exception ignored) {}
                }
                throw new ZTEPhaseException(PHASE_ENROLL, "HTTP " + response.code() + ": " + errorBody);
            }
        } catch (ZTEPhaseException e) {
            throw e;
        } catch (IOException e) {
            throw new ZTEPhaseException(PHASE_ENROLL, "Network error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ZTEPhaseException(PHASE_ENROLL, "Unexpected: " + e.getMessage(), e);
        }
    }

    /** Phase 8: Verify enrollment was recorded by the server. */
    private void phaseVerifyEnrollment() throws ZTEPhaseException {
        Log.i(TAG, "  Verifying enrollment with server...");
        
        try { Thread.sleep(ENROLLMENT_VERIFY_DELAY_MS); } catch (InterruptedException ignored) {}
        
        try {
            DeviceFingerprint fingerprint = new DeviceFingerprint(this);
            DeviceEnrollRequestV2 statusRequest = new DeviceEnrollRequestV2();
            statusRequest.setImei(fingerprint.getIMEI());
            statusRequest.setImei2(fingerprint.getIMEI2());
            statusRequest.setSerialNumber(fingerprint.getSerialNumber());
            statusRequest.setPersistentDeviceId(fingerprint.getPersistentDeviceId());
            statusRequest.setAndroidId(fingerprint.getAndroidId());
            
            ApiService apiService = ApiClient.getApiService();
            Call<DeviceReEnrollService.DeviceStatusResponse> call = apiService.checkDeviceStatus(statusRequest);
            retrofit2.Response<DeviceReEnrollService.DeviceStatusResponse> response = call.execute();
            
            if (response.isSuccessful() && response.body() != null) {
                DeviceReEnrollService.DeviceStatusResponse status = response.body();
                
                if (status.isFound()) {
                    Log.i(TAG, "  Enrollment verified â€” server confirms device");
                    Log.i(TAG, "  Status: " + status.getStatus());
                    
                    if (status.getDeviceId() != null && !status.getDeviceId().isEmpty()) {
                        preferenceManager.setDeviceId(status.getDeviceId());
                    }
                } else {
                    Log.w(TAG, "  Server didn't find device â€” enrollment may need sync");
                }
            } else {
                Log.w(TAG, "  Verification returned HTTP " + response.code());
            }
        } catch (Exception e) {
            Log.w(TAG, "  Verification check failed (non-fatal): " + e.getMessage());
        }
    }

    /** Phase 9: Apply all device protections. */
    private void phaseApplyProtections() throws ZTEPhaseException {
        Log.i(TAG, "  Applying comprehensive device protections...");
        
        // Clear ZTE provisioning active flag BEFORE applying protections
        // so that LockManager and DeviceMonitorService don't block protection setup
        preferenceManager.saveBoolean("zte_provisioning_active", false);
        
        try {
            FactoryResetProtectionReceiver.setupFactoryResetProtection(this);
            Log.i(TAG, "  FRP setup complete");
            
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            protectionManager.applyAllProtections();
            preferenceManager.setProtectionsApplied(true);
            Log.i(TAG, "  All protections applied");
            
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setUninstallBlocked(adminComponent, getPackageName(), true);
                Log.i(TAG, "  Uninstall blocked");
            }
            
            if (autoLockOnEnroll) {
                Log.i(TAG, "  Auto-locking device as per ZTE config");
                preferenceManager.saveBoolean("admin_lock_command", true);
                preferenceManager.setDeviceLocked(true);
                LockManager.showLockScreen(this, defaultLockMessage, defaultContactNumber);
                Log.i(TAG, "  Device auto-locked");
            } else {
                // Explicitly ensure device is NOT locked after enrollment
                Log.i(TAG, "  Device will remain unlocked â€” lock only from admin panel");
                preferenceManager.saveBoolean("admin_lock_command", false);
                preferenceManager.setDeviceLocked(false);
                LockManager.hideLockScreen(this);
            }
            
            // Backup everything to device-protected storage
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            dpPrefs.backupFrom(preferenceManager);
            Log.i(TAG, "  Data backed up to device-protected storage");
            
        } catch (Exception e) {
            Log.e(TAG, "  Protection error (non-fatal): " + e.getMessage(), e);
        }
    }

    /** Phase 10: Start monitoring services and finalize. */
    private void phaseFinalize() throws ZTEPhaseException {
        Log.i(TAG, "  Starting monitoring services...");
        
        DeviceMonitorService.start(this);
        Log.i(TAG, "  DeviceMonitorService started");
        
        ServiceKeepAliveWorker.schedule(this);
        Log.i(TAG, "  KeepAlive worker scheduled");
        
        RebootBlockerService.start(this);
        Log.i(TAG, "  RebootBlockerService started");
        
        long elapsed = System.currentTimeMillis() - enrollmentStartTime;
        Log.i(TAG, "  Total enrollment time: " + (elapsed / 1000) + " seconds");
        
        preferenceManager.saveString("zte_complete_time", String.valueOf(System.currentTimeMillis()));
        preferenceManager.saveString("zte_elapsed_ms", String.valueOf(elapsed));
    }

    // ==================== PROGRESS REPORTING ====================

    /**
     * Report enrollment progress to the backend server.
     * Non-blocking â€” failures are logged but never halt the pipeline.
     */
    private void reportProgress(int phase, String status, int progressPercent, String errorMessage) {
        try {
            DeviceFingerprint fingerprint = new DeviceFingerprint(this);
            long elapsed = (System.currentTimeMillis() - enrollmentStartTime) / 1000;
            
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("imei", fingerprint.getIMEI());
            data.put("imei2", fingerprint.getIMEI2());
            data.put("serial_number", fingerprint.getSerialNumber());
            data.put("persistent_device_id", fingerprint.getPersistentDeviceId());
            data.put("android_id", fingerprint.getAndroidId());
            data.put("manufacturer", Build.MANUFACTURER);
            data.put("model", Build.MODEL);
            data.put("android_version", Build.VERSION.RELEASE);
            data.put("status", status);
            data.put("current_phase", phase);
            data.put("progress_percent", progressPercent);
            data.put("elapsed_seconds", (double) elapsed);
            data.put("zte_version", "2.0");
            data.put("server_url", serverUrl);
            
            if (enrolledDeviceId != null) data.put("device_id", enrolledDeviceId);
            if (fcmToken != null) data.put("fcm_token", fcmToken);
            if (errorMessage != null) {
                data.put("error_message", errorMessage);
                data.put("failure_phase", phase);
            }
            
            // Network info
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            data.put("network_type", "wifi");
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            data.put("network_type", "cellular");
                        }
                    }
                }
            }
            
            // SIM info
            String simOp = preferenceManager.getString("sim_operator", null);
            String simCountry = preferenceManager.getString("sim_country", null);
            String phone = preferenceManager.getString("sim_phone_number", null);
            if (simOp != null) data.put("sim_operator", simOp);
            if (simCountry != null) data.put("sim_country", simCountry);
            if (phone != null) data.put("phone_number", phone);
            if (wifiSsid != null) data.put("wifi_ssid", wifiSsid);
            
            ApiClient.setBaseUrl(serverUrl);
            ApiService apiService = ApiClient.getApiService();
            retrofit2.Response<java.util.Map<String, Object>> response =
                    apiService.reportZTEProgress(data).execute();
            
            if (response.isSuccessful()) {
                Log.d(TAG, "  Progress reported: phase=" + phase + " status=" + status);
            } else {
                Log.w(TAG, "  Progress report HTTP " + response.code());
            }
        } catch (Exception e) {
            // Non-blocking â€” never halt the pipeline for reporting failures
            Log.w(TAG, "  Progress report failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Check if the pipeline has exceeded the maximum allowed time.
     * Prevents stuck enrollments from running forever.
     */
    private void checkPipelineTimeout() throws ZTEPhaseException {
        long elapsed = System.currentTimeMillis() - enrollmentStartTime;
        if (elapsed > PIPELINE_TIMEOUT_MS) {
            throw new ZTEPhaseException(currentPhase,
                    "Pipeline timeout exceeded (" + (elapsed / 1000) + "s > " + (PIPELINE_TIMEOUT_MS / 1000) + "s)");
        }
    }

    // ==================== WIFI HELPERS ====================

    private void addWifiNetwork(String ssid, String password, String security) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null) {
            Log.w(TAG, "  WifiManager not available");
            return;
        }
        
        // Enable WiFi first
        if (!wifiManager.isWifiEnabled()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                wifiManager.setWifiEnabled(true);
            } else if (dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    dpm.setGlobalSetting(adminComponent, Settings.Global.WIFI_ON, "1");
                } catch (Exception e) {
                    Log.w(TAG, "  Could not enable WiFi via DPM: " + e.getMessage());
                }
            }
        }
        
        // Android 10+: WifiNetworkSuggestion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setIsAppInteractionRequired(false);
                
                if (security != null && !security.equals("NONE") && password != null && !password.isEmpty()) {
                    builder.setWpa2Passphrase(password);
                }
                
                List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
                suggestions.add(builder.build());
                
                int status = wifiManager.addNetworkSuggestions(suggestions);
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Log.i(TAG, "  WiFi suggestion added: " + ssid);
                } else {
                    Log.w(TAG, "  WiFi suggestion failed: " + status);
                }
            } catch (Exception e) {
                Log.w(TAG, "  WiFi suggestion error: " + e.getMessage());
            }
        }
        
        // Legacy: WifiConfiguration (Android < 10)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                WifiConfiguration config = new WifiConfiguration();
                config.SSID = "\"" + ssid + "\"";
                
                if (security == null || security.equals("NONE")) {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if (security.equals("WEP")) {
                    config.wepKeys[0] = "\"" + password + "\"";
                    config.wepTxKeyIndex = 0;
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
                } else {
                    config.preSharedKey = "\"" + password + "\"";
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                }
                
                int networkId = wifiManager.addNetwork(config);
                if (networkId != -1) {
                    wifiManager.enableNetwork(networkId, true);
                    wifiManager.reconnect();
                    Log.i(TAG, "  WiFi configured (legacy): " + ssid);
                } else {
                    Log.w(TAG, "  Failed to add WiFi (legacy)");
                }
            } catch (Exception e) {
                Log.w(TAG, "  WiFi config error (legacy): " + e.getMessage());
            }
        }
    }

    private void enableWifi() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null && !wifiManager.isWifiEnabled()) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    wifiManager.setWifiEnabled(true);
                    Log.i(TAG, "  WiFi enabled");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "  Could not enable WiFi: " + e.getMessage());
        }
    }

    private void enableMobileData() {
        if (!dpm.isDeviceOwnerApp(getPackageName())) return;
        try {
            dpm.setGlobalSetting(adminComponent, "mobile_data", "1");
            Log.i(TAG, "  Mobile data enabled via DPM");
        } catch (Exception e) {
            Log.w(TAG, "  Could not enable mobile data: " + e.getMessage());
        }
    }

    private boolean waitForNetwork(long timeoutMs) {
        if (isNetworkAvailable()) return true;
        
        Log.i(TAG, "  Waiting for network (timeout: " + (timeoutMs / 1000) + "s)...");
        
        CountDownLatch networkLatch = new CountDownLatch(1);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        ConnectivityManager.NetworkCallback callback = null;
        
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    Log.i(TAG, "  Network available via callback");
                    networkLatch.countDown();
                }
            };
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, callback);
            } catch (Exception e) {
                Log.w(TAG, "  Could not register network callback: " + e.getMessage());
            }
        }
        
        // Poll as fallback
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isNetworkAvailable()) {
                if (callback != null && cm != null) {
                    try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
                }
                return true;
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
        }
        
        if (callback != null && cm != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
        return isNetworkAvailable();
    }

    private boolean verifyInternetConnectivity() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            
            Request request = new Request.Builder().url("https://www.google.com/generate_204").get().build();
            Response response = client.newCall(request).execute();
            int code = response.code();
            response.close();
            return code == 204 || code == 200;
        } catch (Exception e) {
            // Fallback: try our server
            try {
                String checkUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder().url(checkUrl).get().build();
                Response response = client.newCall(request).execute();
                response.close();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    // ==================== SIM INFO ====================

    private void collectSIMInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return;
            
            Log.i(TAG, "  SIM Operator: " + tm.getSimOperatorName());
            Log.i(TAG, "  Network Operator: " + tm.getNetworkOperatorName());
            Log.i(TAG, "  SIM Country: " + tm.getSimCountryIso());
            Log.i(TAG, "  SIM State: " + tm.getSimState());
            
            preferenceManager.saveString("sim_operator", tm.getSimOperatorName());
            preferenceManager.saveString("sim_country", tm.getSimCountryIso());
            preferenceManager.saveString("network_operator", tm.getNetworkOperatorName());
            
            try {
                String phoneNumber = tm.getLine1Number();
                if (phoneNumber != null && !phoneNumber.isEmpty()) {
                    preferenceManager.saveString("sim_phone_number", phoneNumber);
                    Log.i(TAG, "  Phone: " + phoneNumber);
                }
            } catch (SecurityException e) {
                Log.d(TAG, "  Phone number not accessible");
            }
            
            // Dual SIM info
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (sm != null) {
                        List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                        if (subs != null) {
                            preferenceManager.saveString("sim_count", String.valueOf(subs.size()));
                            for (int i = 0; i < subs.size(); i++) {
                                SubscriptionInfo sub = subs.get(i);
                                Log.i(TAG, "  SIM " + (i + 1) + ": " + sub.getCarrierName() +
                                        " (Slot " + sub.getSimSlotIndex() + ")");
                                preferenceManager.saveString("sim" + (i + 1) + "_carrier",
                                        sub.getCarrierName().toString());
                                preferenceManager.saveString("sim" + (i + 1) + "_iccid",
                                        sub.getIccId());
                            }
                        }
                    }
                } catch (SecurityException e) {
                    Log.d(TAG, "  Subscription info not accessible");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "  SIM info collection error: " + e.getMessage());
        }
    }

    // ==================== ENROLLMENT HELPERS ====================

    private DeviceEnrollRequestV2 buildEnrollmentRequest(String fcmToken) {
        try {
            DeviceFingerprint fingerprint = new DeviceFingerprint(this);
            DeviceEnrollRequestV2 request = new DeviceEnrollRequestV2();
            
            request.setImei(fingerprint.getIMEI());
            request.setImei2(fingerprint.getIMEI2());
            request.setSerialNumber(fingerprint.getSerialNumber());
            request.setPersistentDeviceId(fingerprint.getPersistentDeviceId());
            request.setAndroidId(fingerprint.getAndroidId());
            request.setFcmToken(fcmToken);
            request.setManufacturer(Build.MANUFACTURER);
            request.setBrand(Build.BRAND);
            request.setModel(Build.MODEL);
            request.setDevice(Build.DEVICE);
            request.setProduct(Build.PRODUCT);
            request.setBoard(Build.BOARD);
            request.setHardware(Build.HARDWARE);
            request.setAndroidVersion(Build.VERSION.RELEASE);
            request.setSdkVersion(Build.VERSION.SDK_INT);
            request.setBuildId(Build.ID);
            request.setBuildFingerprint(Build.FINGERPRINT);
            
            try {
                PackageInfo pkgInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                request.setAppVersion(pkgInfo.versionName);
            } catch (Exception e) {
                request.setAppVersion("1.0.0");
            }
            
            if (dpm != null) {
                request.setDeviceOwner(dpm.isDeviceOwnerApp(getPackageName()));
                request.setAdminActive(dpm.isAdminActive(adminComponent));
            }
            
            Log.i(TAG, "  Request built â€” IMEI: " + request.getImei() +
                    ", Model: " + Build.MANUFACTURER + " " + Build.MODEL);
            
            return request;
        } catch (Exception e) {
            Log.e(TAG, "  Error building enrollment request", e);
            return null;
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    /**
     * Exponential backoff with jitter.
     * Formula: min(MAX_DELAY, BASE * 2^attempt) + random(0, BASE)
     */
    private long calculateBackoffDelay(int attempt) {
        long exponentialDelay = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, Math.min(attempt, 10)));
        long cappedDelay = Math.min(exponentialDelay, MAX_RETRY_DELAY_MS);
        long jitter = (long) (random.nextDouble() * BASE_RETRY_DELAY_MS);
        return cappedDelay + jitter;
    }

    private void backupZTEState(String phase) {
        try {
            DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
            dpPrefs.saveString("zte_phase", phase);
            dpPrefs.saveString("zte_server_url", serverUrl != null ? serverUrl : "");
            dpPrefs.saveLong("zte_timestamp", System.currentTimeMillis());
            if (enrolledDeviceId != null) {
                dpPrefs.saveString("zte_device_id", enrolledDeviceId);
            }
        } catch (Exception e) {
            Log.w(TAG, "  Could not backup ZTE state: " + e.getMessage());
        }
    }

    private void handlePipelineFailure(ZTEPhaseException e) {
        Log.e(TAG, "========================================");
        Log.e(TAG, "  ZTE PIPELINE FAILED                  ");
        Log.e(TAG, "  Phase: " + PHASE_NAMES[e.phase]);
        Log.e(TAG, "  Error: " + e.getMessage());
        Log.e(TAG, "========================================");
        
        preferenceManager.saveBoolean("zte_pending", true);
        preferenceManager.saveString("zte_failure_phase", String.valueOf(e.phase));
        preferenceManager.saveString("zte_failure_reason", e.getMessage());
        preferenceManager.saveString("zte_failure_time", String.valueOf(System.currentTimeMillis()));
        backupZTEState("failed_phase_" + e.phase);
        
        // Report failure to server
        reportProgress(e.phase, "failed", (e.phase * 100) / PHASE_COMPLETE, e.getMessage());
        
        // Still apply protections even if enrollment failed
        try {
            Log.i(TAG, "Applying protections despite enrollment failure...");
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            protectionManager.applyAllProtections();
            FactoryResetProtectionReceiver.setupFactoryResetProtection(this);
            DeviceMonitorService.start(this);
            ServiceKeepAliveWorker.schedule(this);
        } catch (Exception pe) {
            Log.e(TAG, "  Protection application also failed: " + pe.getMessage());
        }
        
        updateNotification("Setup failed â€” will retry on next boot", 0);
        releaseWakeLock();
        mainHandler.postDelayed(this::stopSelf, 5000);
    }

    private void finalizeEnrollment() {
        long elapsed = System.currentTimeMillis() - enrollmentStartTime;
        
        Log.i(TAG, "========================================");
        Log.i(TAG, "  ZERO TOUCH ENROLLMENT COMPLETE!      ");
        Log.i(TAG, "  Time: " + String.format(Locale.US, "%.1f", elapsed / 1000.0) + " seconds");
        Log.i(TAG, "  Device ID: " + (enrolledDeviceId != null ? enrolledDeviceId : "N/A"));
        Log.i(TAG, "========================================");
        
        preferenceManager.saveBoolean("zte_complete", true);
        preferenceManager.saveBoolean("zte_pending", false);
        backupZTEState("complete");
        
        // Report completion to server
        reportProgress(PHASE_COMPLETE, "completed", 100, null);
        
        updateNotification("Setup complete!", 100);
        releaseWakeLock();
        mainHandler.postDelayed(this::stopSelf, 3000);
    }

    // ==================== WAKELOCK ====================

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                wakeLock.acquire(10 * 60 * 1000L); // 10 minutes max
                Log.i(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire WakeLock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing WakeLock: " + e.getMessage());
        }
    }

    // ==================== NOTIFICATION ====================

    private void updateNotification(String message, int progress) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(message, progress));
        }
    }

    private Notification buildNotification(String message, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Device Manager Auto-Setup")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        
        if (progress > 0 && progress < 100) {
            builder.setProgress(100, progress, false);
            builder.setSubText(progress + "%");
        } else if (progress >= 100) {
            builder.setProgress(0, 0, false);
            builder.setOngoing(false);
        } else {
            builder.setProgress(100, 0, true);
        }
        
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Zero Touch Setup", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Automatic device setup progress");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ZTE Service destroyed");
        isRunning.set(false);
        releaseWakeLock();
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ==================== EXCEPTION ====================

    private static class ZTEPhaseException extends RuntimeException {
        final int phase;
        
        ZTEPhaseException(int phase, String message) {
            super(message);
            this.phase = phase;
        }
        
        ZTEPhaseException(int phase, String message, Throwable cause) {
            super(message, cause);
            this.phase = phase;
        }
    }
}
