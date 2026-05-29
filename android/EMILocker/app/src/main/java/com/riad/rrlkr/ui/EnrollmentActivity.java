package com.riad.rrlkr.ui;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.riad.rrlkr.BuildConfig;
import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.DeviceEnrollRequestV2;
import com.riad.rrlkr.receiver.FactoryResetProtectionReceiver;
import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceOwnerActivator;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.DeviceReEnrollService;
import com.riad.rrlkr.utils.DeviceFingerprint;
import com.riad.rrlkr.utils.PreferenceManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Enrollment Activity - Handles device registration with server
 * Collects full device fingerprint including IMEI, Serial, etc.
 */
public class EnrollmentActivity extends AppCompatActivity {

    private static final String TAG = "EnrollmentActivity";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int PERMISSION_LOCATION_CAMERA = 124;
    private static final int PERMISSION_BACKGROUND_LOCATION = 125;

    private EditText etServerUrl;
    private Button btnEnroll;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvDeviceInfo;
    private CardView cardDeviceOwnerSetup;
    private Button btnSetupDeviceOwner;
    private Button btnOpenDevSettings;
    private Button btnCopyAdbCommand;
    private TextView tvDoInstructions;
    
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    private DeviceFingerprint deviceFingerprint;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enrollment);
        
        preferenceManager = PreferenceManager.getInstance(this);
        apiService = ApiClient.getClient().create(ApiService.class);
        deviceFingerprint = new DeviceFingerprint(this);
        
        // Check if already enrolled via Zero Touch Enrollment
        if (preferenceManager.isEnrolled() && 
            preferenceManager.getBoolean("zte_enrolled", false)) {
            Log.i(TAG, "Already enrolled via Zero Touch â€” closing EnrollmentActivity");
            Toast.makeText(this, "âœ… Device already enrolled (Zero Touch)", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Initialize views
        etServerUrl = findViewById(R.id.et_server_url);
        btnEnroll = findViewById(R.id.btn_enroll);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        cardDeviceOwnerSetup = findViewById(R.id.card_device_owner_setup);
        btnSetupDeviceOwner = findViewById(R.id.btn_setup_device_owner);
        tvDoInstructions = findViewById(R.id.tv_do_instructions);
        
        // Always use the live API URL - fixed and not editable
        String liveUrl = BuildConfig.SERVER_URL;
        etServerUrl.setText(liveUrl);
        etServerUrl.setEnabled(false);
        etServerUrl.setFocusable(false);
        // Clear any previously saved wrong URL and save the correct live URL
        preferenceManager.saveString("server_url", liveUrl);
        ApiClient.setBaseUrl(liveUrl);

        // Request necessary permissions
        checkAndRequestPermissions();

        // Display device info
        displayDeviceInfo();
        
        // Check device owner status
        checkDeviceOwnerStatus();
        
        // Enroll button
        btnEnroll.setOnClickListener(v -> enrollDevice());
        
        // Setup Device Owner button
        btnSetupDeviceOwner.setOnClickListener(v -> attemptDeviceOwnerSetup());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check device owner status when returning from settings
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            tvStatus.setText("âœ… Device Owner Mode Active");
            cardDeviceOwnerSetup.setVisibility(View.GONE);
            displayDeviceInfo();
        } else {
            showDeviceOwnerSetupCard();
        }
    }

    // Removed all manual/PC/ADB/QR setup buttons and instructions

    /**
     * Check and request READ_PHONE_STATE, LOCATION, and CAMERA permissions
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // First request READ_PHONE_STATE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        PERMISSION_REQUEST_CODE);
                Log.i(TAG, "Requesting READ_PHONE_STATE permission");
            } else {
                Log.i(TAG, "READ_PHONE_STATE permission already granted");
                // Now check location and camera
                requestLocationAndCameraPermissions();
            }
        }
    }

    /**
     * Request location and camera permissions for GPS tracking and remote camera
     */
    private void requestLocationAndCameraPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
            boolean needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED;

            if (needLocation || needCamera) {
                java.util.List<String> perms = new java.util.ArrayList<>();
                if (needLocation) {
                    perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
                    perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
                if (needCamera) {
                    perms.add(Manifest.permission.CAMERA);
                }
                ActivityCompat.requestPermissions(this,
                        perms.toArray(new String[0]),
                        PERMISSION_LOCATION_CAMERA);
                Log.i(TAG, "Requesting Location + Camera permissions");
            } else {
                // All granted, request background location on Android 10+
                requestBackgroundLocation();
            }
        }
    }

    /**
     * Request background location permission (needed on Android 10+)
     */
    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        PERMISSION_BACKGROUND_LOCATION);
                Log.i(TAG, "Requesting ACCESS_BACKGROUND_LOCATION permission");
            }
        }
    }

    /**
     * Handle permission request result
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "READ_PHONE_STATE permission granted");
                Toast.makeText(this, "Permission granted! Refreshing device info...", Toast.LENGTH_SHORT).show();
                displayDeviceInfo();
            } else {
                Log.w(TAG, "READ_PHONE_STATE permission denied");
                Toast.makeText(this,
                        "âš  Permission denied! IMEI and Serial Number cannot be accessed.\n" +
                        "Please grant permission in Settings to enroll device.",
                        Toast.LENGTH_LONG).show();
            }
            // Now request location and camera
            requestLocationAndCameraPermissions();
            
        } else if (requestCode == PERMISSION_LOCATION_CAMERA) {
            boolean locationGranted = false;
            boolean cameraGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    locationGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                } else if (permissions[i].equals(Manifest.permission.CAMERA)) {
                    cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }
            Log.i(TAG, "Location permission: " + (locationGranted ? "GRANTED" : "DENIED"));
            Log.i(TAG, "Camera permission: " + (cameraGranted ? "GRANTED" : "DENIED"));
            
            if (locationGranted) {
                requestBackgroundLocation();
            }
            
        } else if (requestCode == PERMISSION_BACKGROUND_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Background Location permission granted");
            } else {
                Log.w(TAG, "Background Location permission denied");
            }
        }
    }

    private void displayDeviceInfo() {
        StringBuilder info = new StringBuilder();
        
        String imei = deviceFingerprint.getIMEI();
        String imei2 = deviceFingerprint.getIMEI2();
        String serial = deviceFingerprint.getSerialNumber();
        boolean isDeviceOwner = deviceFingerprint.isDeviceOwner();
        
        // Device Owner Status
        if (isDeviceOwner) {
            info.append("âœ… Device Owner: ACTIVE\n\n");
        } else {
            info.append("âš ï¸ Device Owner: NOT SET\n\n");
        }
        
        // IMEI display
        if (imei != null && !imei.isEmpty()) {
            info.append("ðŸ“± IMEI 1: ").append(imei).append("\n");
        } else {
            info.append("ðŸ“± IMEI 1: â›” Requires Device Owner\n");
        }
        
        if (imei2 != null && !imei2.isEmpty()) {
            info.append("ðŸ“± IMEI 2: ").append(imei2).append("\n");
        }
        
        // Serial display
        if (serial != null && !serial.isEmpty() && !serial.equals("unknown")) {
            info.append("ðŸ”¢ Serial: ").append(serial).append("\n");
        } else {
            info.append("ðŸ”¢ Serial: â›” Requires Device Owner\n");
        }
        
        info.append("\n");
        info.append("ðŸ“¦ Model: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("ðŸ¤– Android: ").append(Build.VERSION.RELEASE).append("\n");
        
        // Show device owner setup prompt if needed (no ADB commands shown)
        if (!isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DeviceOwnerActivator activator = new DeviceOwnerActivator(this);
            int accountCount = activator.getAccountCount();
            
            if (accountCount > 0) {
                info.append("\nâš ï¸ ").append(accountCount).append(" account(s) found.\n");
                info.append("â†’ Remove all accounts for setup\n");
            }
        }
        
        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText(info.toString());
        }
        tvStatus.setText("Ready to enroll");
    }
    
    private void checkDeviceOwnerStatus() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
        
        if (dpm != null) {
            boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());
            boolean isAdminActive = dpm.isAdminActive(adminComponent);
            
            if (isDeviceOwner) {
                tvStatus.setText("âœ… Device Owner Mode Active");
                cardDeviceOwnerSetup.setVisibility(View.GONE);
            } else if (isAdminActive) {
                tvStatus.setText("âš ï¸ Admin Active - Device Owner not set");
                showDeviceOwnerSetupCard();
                // Try root/shell silently (won't show "Contact IT" dialog)
                trySilentDeviceOwnerActivation();
            } else {
                tvStatus.setText("âŒ Device Admin Not Active - Requesting...");
                showDeviceOwnerSetupCard();
                // Request Device Admin first (this is safe and shows a proper dialog)
                requestDeviceAdmin();
            }
        }
    }

    /**
     * Show the Device Owner setup card with instructions
     */
    private void showDeviceOwnerSetupCard() {
        cardDeviceOwnerSetup.setVisibility(View.VISIBLE);
        
        DeviceOwnerActivator activator = new DeviceOwnerActivator(this);
        int accountCount = activator.getAccountCount();
        boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung");
        
        StringBuilder instructions = new StringBuilder();
        instructions.append("Device Owner is needed for full device protection.\n\n");
        
        if (isSamsung) {
            instructions.append("ðŸ“± Samsung device detected.\n");
            instructions.append("Samsung needs extra steps for setup.\n\n");
        }
        
        if (accountCount > 0) {
            instructions.append("âš ï¸ ").append(accountCount).append(" account(s) found on device!\n");
            if (isSamsung) {
                instructions.append("Remove ALL accounts (Google + Samsung).\n");
                instructions.append("Also disable Find My Mobile.\n\n");
            } else {
                instructions.append("Remove all accounts to proceed.\n\n");
            }
        } else {
            instructions.append("âœ… Ready for setup.\n\n");
        }
        
        instructions.append("Tap 'Setup Device Owner' to begin.");
        
        tvDoInstructions.setText(instructions.toString());
    }

    /**
     * Try root/shell Device Owner activation silently.
     * Does NOT try managed provisioning (which shows "Contact IT" error).
     */
    private void trySilentDeviceOwnerActivation() {
        new Thread(() -> {
            DeviceOwnerActivator activator = new DeviceOwnerActivator(this);
            
            // Only try root and shell methods - they don't show UI dialogs
            activator.activate(new DeviceOwnerActivator.ActivationCallback() {
                @Override
                public void onSuccess(String method) {
                    runOnUiThread(() -> {
                        if ("already_active".equals(method)) {
                            tvStatus.setText("âœ… Device Owner Mode Active");
                            cardDeviceOwnerSetup.setVisibility(View.GONE);
                        } else {
                            tvStatus.setText("âœ… Device Owner Auto-Activated! (" + method + ")");
                            cardDeviceOwnerSetup.setVisibility(View.GONE);
                            Toast.makeText(EnrollmentActivity.this,
                                "âœ… Device Owner activated automatically!",
                                Toast.LENGTH_LONG).show();
                        }
                        displayDeviceInfo();
                    });
                }

                @Override
                public void onFailed(String reason, String instructions) {
                    runOnUiThread(() -> {
                        tvStatus.setText("âš ï¸ Device Owner requires manual setup");
                        // Card is already visible with instructions
                    });
                }
            });
        }).start();
    }

    /**
     * User tapped "Setup Device Owner" button â€” try all methods with user feedback
     */
    private void attemptDeviceOwnerSetup() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        
        // Check if already device owner
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            tvStatus.setText("âœ… Device Owner is already active!");
            cardDeviceOwnerSetup.setVisibility(View.GONE);
            displayDeviceInfo();
            Toast.makeText(this, "âœ… Device Owner already active!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check for blocking accounts
        DeviceOwnerActivator activator = new DeviceOwnerActivator(this);
        int accountCount = activator.getAccountCount();
        
        if (accountCount > 0) {
            new AlertDialog.Builder(this)
                .setTitle("âš ï¸ Accounts Found")
                .setMessage("You have " + accountCount + " account(s) on this device.\n\n" +
                    "Device Owner CANNOT be set while accounts exist.\n\n" +
                    "Please remove ALL accounts first:\n" +
                    "Settings â†’ Accounts â†’ Remove each account")
                .setPositiveButton("Open Accounts Settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        // No accounts â€” try to activate
        tvStatus.setText("â³ Attempting Device Owner setup...");
        btnSetupDeviceOwner.setEnabled(false);
        
        new Thread(() -> {
            activator.activate(new DeviceOwnerActivator.ActivationCallback() {
                @Override
                public void onSuccess(String method) {
                    runOnUiThread(() -> {
                        btnSetupDeviceOwner.setEnabled(true);
                        tvStatus.setText("âœ… Device Owner Activated! (" + method + ")");
                        cardDeviceOwnerSetup.setVisibility(View.GONE);
                        displayDeviceInfo();
                        Toast.makeText(EnrollmentActivity.this,
                            "âœ… Device Owner mode activated!",
                            Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onFailed(String reason, String instructions) {
                    runOnUiThread(() -> {
                        btnSetupDeviceOwner.setEnabled(true);
                        tvStatus.setText("\u26A0\uFE0F In-app setup blocked by Android.\nRun setup_owner_wifi.bat on the shop PC (no USB needed).");
                        Toast.makeText(EnrollmentActivity.this,
                            "Android blocks in-app Device Owner once accounts are signed in. " +
                            "Run setup_owner_wifi.bat from the dashboard on the shop PC \u2014 Wi-Fi only, no cable.",
                            Toast.LENGTH_LONG).show();
                    });
                }
            });
        }).start();
    }

    /**
     * Auto-request Device Admin activation
     */
    private void requestDeviceAdmin() {
        try {
            ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "RR Device Manager requires admin access to protect your device.");
            startActivityForResult(intent, 999);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting device admin", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 999) {
            // Device admin request completed
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "Device Admin activated - now trying Device Owner silently");
                tvStatus.setText("âœ… Admin Active - Checking Device Owner...");
                trySilentDeviceOwnerActivation();
            } else {
                tvStatus.setText("âš ï¸ Admin activation declined");
                showDeviceOwnerSetupCard();
            }
            displayDeviceInfo();
        }
    }

    private void enrollDevice() {
        // Always use the fixed live API URL
        String serverUrl = BuildConfig.SERVER_URL;
        etServerUrl.setText(serverUrl);

        // Ensure API client uses live URL
        ApiClient.setBaseUrl(serverUrl);
        preferenceManager.saveString("server_url", serverUrl);
        apiService = ApiClient.getApiService();

        // Show progress
        setLoading(true);
        tvStatus.setText("Enrolling device...");

        // Build enrollment request with full fingerprint
        DeviceEnrollRequestV2 request = buildEnrollmentRequest();

        // Log enrollment attempt (no URL in logs for security)
        Log.i(TAG, "=== Starting Device Enrollment ===");
        Log.i(TAG, "IMEI: " + (request.getImei() != null ? "present" : "NULL"));
        Log.i(TAG, "Device: " + request.getManufacturer() + " " + request.getModel());

        // Send enrollment request
        apiService.enrollDevice(request).enqueue(new Callback<DeviceReEnrollService.EnrollResponse>() {
            @Override
            public void onResponse(Call<DeviceReEnrollService.EnrollResponse> call, 
                                   Response<DeviceReEnrollService.EnrollResponse> response) {
                setLoading(false);
                
                if (response.isSuccessful() && response.body() != null) {
                    DeviceReEnrollService.EnrollResponse result = response.body();
                    Log.i(TAG, "Device enrolled successfully");
                    Log.i(TAG, "Device ID: " + result.getDeviceId());
                    Log.i(TAG, "Device Token: " + result.getDeviceToken());

                    // Save enrollment info
                    preferenceManager.setEnrolled(true);
                    preferenceManager.saveString("device_token", result.getDeviceToken());
                    
                    // Save device ID for pending commands check
                    if (result.getDeviceId() != null) {
                        preferenceManager.setDeviceId(result.getDeviceId());
                        Log.i(TAG, "Device ID saved: " + result.getDeviceId());
                    }

                    // Store device fingerprint locally (lightweight, OK on UI thread)
                    deviceFingerprint.storeFingerprint();

                    Toast.makeText(EnrollmentActivity.this,
                        "âœ… Device enrolled! Applying protections...",
                        Toast.LENGTH_SHORT).show();
                    tvStatus.setText("âœ… Enrolled. Applying protections (please wait)...");

                    // CRITICAL: applyAllProtections() makes dozens of synchronous DPM calls
                    // (setPermissionGrantState, addUserRestriction, setSecureSetting, etc.).
                    // Running it on the UI thread causes ANR and on some MTK devices
                    // triggers a NullPointerException in the system PermissionController
                    // (ThemeIconUtil.getIconShape), which makes the app appear to "close"
                    // immediately after pressing Enroll. Run it on a background thread.
                    final Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        try {
                            // Setup Factory Reset Protection
                            FactoryResetProtectionReceiver.setupFactoryResetProtection(appCtx);
                        } catch (Throwable t) {
                            Log.e(TAG, "FRP setup failed", t);
                        }
                        try {
                            // Apply ALL device protections (IMEI binding, USB block, uninstall block, etc.)
                            DeviceProtectionManager protectionManager = new DeviceProtectionManager(appCtx);
                            protectionManager.applyAllProtections();
                            preferenceManager.saveBoolean("protections_applied", true);
                        } catch (Throwable t) {
                            Log.e(TAG, "applyAllProtections failed", t);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(EnrollmentActivity.this,
                                "âœ… Protections applied successfully!",
                                Toast.LENGTH_LONG).show();
                            tvStatus.setText("âœ… Enrolled Successfully!\nIMEI bound. Protections active.\nDevice data sent to admin panel.");

                            // Start monitoring service
                            DeviceMonitorService.start(EnrollmentActivity.this);

                            // Close activity after delay
                            btnEnroll.postDelayed(() -> {
                                if (!isFinishing()) finish();
                            }, 2000);
                        });
                    }, "ApplyProtections").start();

                } else {
                    int code = response.code();
                    String rawBody = "";
                    if (response.errorBody() != null) {
                        try {
                            rawBody = response.errorBody().string();
                            Log.e(TAG, "Error body: " + rawBody);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to read error body", e);
                        }
                    }
                    String renderRouting = response.headers().get("x-render-routing");
                    String error;
                    if ("suspend".equalsIgnoreCase(renderRouting)
                            || rawBody.contains("Service Suspended")
                            || rawBody.contains("has been suspended")) {
                        error = "Server is SUSPENDED on Render.\n" +
                                "Open dashboard.render.com and click Resume on rr-locker-api.";
                    } else if (code == 502 || code == 503 || code == 504) {
                        error = "Server unavailable (HTTP " + code + ").\n" +
                                "It may be cold-starting or down. Try again in 30-60 seconds.";
                    } else if (code == 403 && rawBody.contains("Just a moment")) {
                        error = "Cloudflare challenge blocked the request. Try again.";
                    } else {
                        error = "Enrollment failed: HTTP " + code;
                    }
                    Log.e(TAG, error);
                    tvStatus.setText("\u274C " + error);
                    Toast.makeText(EnrollmentActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
            
            @Override
            public void onFailure(Call<DeviceReEnrollService.EnrollResponse> call, Throwable t) {
                setLoading(false);
                String error;

                if (t instanceof java.net.UnknownHostException) {
                    error = "Cannot reach server. Check:\n" +
                            "1. Internet connection is active\n" +
                            "2. Try again later";
                } else if (t instanceof java.net.SocketTimeoutException) {
                    error = "Connection timeout. Try again later.";
                } else if (t instanceof java.net.ConnectException) {
                    error = "Connection failed. Try again later.";
                } else {
                    error = "Network error. Check your connection.";
                }

                Log.e(TAG, "âŒ Enrollment failed", t);
                Log.e(TAG, "Error type: " + t.getClass().getSimpleName());
                tvStatus.setText("âŒ " + error);
                Toast.makeText(EnrollmentActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }
    
    private DeviceEnrollRequestV2 buildEnrollmentRequest() {
        DeviceEnrollRequestV2 request = new DeviceEnrollRequestV2();
        
        // Hardware identifiers
        String imei = deviceFingerprint.getIMEI();
        request.setImei(imei);
        request.setImei2(deviceFingerprint.getIMEI2());
        request.setSerialNumber(deviceFingerprint.getSerialNumber());
        request.setPersistentDeviceId(deviceFingerprint.getPersistentDeviceId());
        request.setAndroidId(deviceFingerprint.getAndroidId());
        
        // Store IMEI for binding (critical for device protection)
        if (imei != null && !imei.isEmpty()) {
            preferenceManager.setStoredIMEI(imei);
            Log.i(TAG, "IMEI bound during enrollment: " + imei);
        }
        
        // FCM Token
        request.setFcmToken(preferenceManager.getString("fcm_token", ""));
        
        // Device info
        request.setManufacturer(Build.MANUFACTURER);
        request.setBrand(Build.BRAND);
        request.setModel(Build.MODEL);
        request.setDevice(Build.DEVICE);
        request.setProduct(Build.PRODUCT);
        request.setBoard(Build.BOARD);
        request.setHardware(Build.HARDWARE);
        
        // Software info
        request.setAndroidVersion(Build.VERSION.RELEASE);
        request.setSdkVersion(Build.VERSION.SDK_INT);
        request.setBuildId(Build.ID);
        request.setBuildFingerprint(Build.FINGERPRINT);
        
        // App info
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            request.setAppVersion(version);
        } catch (Exception e) {
            request.setAppVersion("1.0.0");
        }
        
        // Admin status
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
        if (dpm != null) {
            request.setDeviceOwner(dpm.isDeviceOwnerApp(getPackageName()));
            request.setAdminActive(dpm.isAdminActive(adminComponent));
        }
        
        return request;
    }
    
    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnEnroll.setEnabled(!loading);
        // Server URL field stays disabled (non-editable)
    }
}
