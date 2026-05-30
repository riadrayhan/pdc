package com.riad.rrlkr.ui;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.DeviceReEnrollService;
import com.riad.rrlkr.utils.DeviceFingerprint;
import com.riad.rrlkr.utils.PreferenceManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Enrollment Activity - Handles device registration with server.
 * Collects full device fingerprint (IMEI, Serial, etc.) and enrolls the
 * device. Protection is enforced through Device Admin (the user grants it
 * with one tap); Device Owner / provisioning flows have been removed.
 */
public class EnrollmentActivity extends AppCompatActivity {

    private static final String TAG = "EnrollmentActivity";
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int PERMISSION_LOCATION_CAMERA = 124;
    private static final int PERMISSION_BACKGROUND_LOCATION = 125;
    private static final int REQUEST_DEVICE_ADMIN = 999;

    private EditText etServerUrl;
    private Button btnEnroll;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private TextView tvDeviceInfo;

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

        // Initialize views
        etServerUrl = findViewById(R.id.et_server_url);
        btnEnroll = findViewById(R.id.btn_enroll);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvDeviceInfo = findViewById(R.id.tv_device_info);

        // Always use the live API URL - fixed and not editable
        String liveUrl = BuildConfig.SERVER_URL;
        etServerUrl.setText(liveUrl);
        etServerUrl.setEnabled(false);
        etServerUrl.setFocusable(false);
        preferenceManager.saveString("server_url", liveUrl);
        ApiClient.setBaseUrl(liveUrl);

        // Request necessary permissions
        checkAndRequestPermissions();

        // Display device info
        displayDeviceInfo();

        // Ensure Device Admin is active (user taps Allow - no PC needed)
        if (!EMIDeviceAdminReceiver.isAdminActive(this)) {
            requestDeviceAdmin();
        }

        // Enroll button
        btnEnroll.setOnClickListener(v -> enrollDevice());
    }

    @Override
    protected void onResume() {
        super.onResume();
        displayDeviceInfo();
    }

    /**
     * Check and request READ_PHONE_STATE, LOCATION, and CAMERA permissions
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        PERMISSION_REQUEST_CODE);
                Log.i(TAG, "Requesting READ_PHONE_STATE permission");
            } else {
                Log.i(TAG, "READ_PHONE_STATE permission already granted");
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
                        "Permission denied. IMEI and Serial Number cannot be accessed.\n" +
                        "Please grant permission in Settings to enroll device.",
                        Toast.LENGTH_LONG).show();
            }
            requestLocationAndCameraPermissions();

        } else if (requestCode == PERMISSION_LOCATION_CAMERA) {
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    locationGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }
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
        boolean isAdmin = EMIDeviceAdminReceiver.isAdminActive(this);

        info.append("Device Admin: ").append(isAdmin ? "ACTIVE" : "NOT active").append("\n\n");

        if (imei != null && !imei.isEmpty()) {
            info.append("IMEI 1: ").append(imei).append("\n");
        }
        if (imei2 != null && !imei2.isEmpty()) {
            info.append("IMEI 2: ").append(imei2).append("\n");
        }
        if (serial != null && !serial.isEmpty() && !serial.equals("unknown")) {
            info.append("Serial: ").append(serial).append("\n");
        }

        info.append("\n");
        info.append("Model: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("Android: ").append(Build.VERSION.RELEASE).append("\n");

        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText(info.toString());
        }
        tvStatus.setText("Ready to enroll");
    }

    private void requestDeviceAdmin() {
        try {
            ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "RR Device Manager requires admin access to protect your device.");
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting device admin", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "Device Admin activated");
                tvStatus.setText("Device Admin active");
                DeviceMonitorService.start(this);
            } else {
                tvStatus.setText("Device Admin activation declined");
            }
            displayDeviceInfo();
        }
    }

    private void enrollDevice() {
        // Always use the fixed live API URL
        String serverUrl = BuildConfig.SERVER_URL;
        etServerUrl.setText(serverUrl);

        ApiClient.setBaseUrl(serverUrl);
        preferenceManager.saveString("server_url", serverUrl);
        apiService = ApiClient.getApiService();

        setLoading(true);
        tvStatus.setText("Enrolling device...");

        DeviceEnrollRequestV2 request = buildEnrollmentRequest();

        Log.i(TAG, "=== Starting Device Enrollment ===");
        Log.i(TAG, "IMEI: " + (request.getImei() != null ? "present" : "NULL"));
        Log.i(TAG, "Device: " + request.getManufacturer() + " " + request.getModel());

        apiService.enrollDevice(request).enqueue(new Callback<DeviceReEnrollService.EnrollResponse>() {
            @Override
            public void onResponse(Call<DeviceReEnrollService.EnrollResponse> call,
                                   Response<DeviceReEnrollService.EnrollResponse> response) {
                setLoading(false);

                if (response.isSuccessful() && response.body() != null) {
                    DeviceReEnrollService.EnrollResponse result = response.body();
                    Log.i(TAG, "Device enrolled successfully");
                    Log.i(TAG, "Device ID: " + result.getDeviceId());

                    preferenceManager.setEnrolled(true);
                    preferenceManager.saveString("device_token", result.getDeviceToken());

                    if (result.getDeviceId() != null) {
                        preferenceManager.setDeviceId(result.getDeviceId());
                        Log.i(TAG, "Device ID saved: " + result.getDeviceId());
                    }

                    deviceFingerprint.storeFingerprint();

                    Toast.makeText(EnrollmentActivity.this,
                        "Device enrolled! Applying protections...",
                        Toast.LENGTH_SHORT).show();
                    tvStatus.setText("Enrolled. Applying protections (please wait)...");

                    // applyAllProtections() makes many synchronous DPM calls; run off the UI thread.
                    final Context appCtx = getApplicationContext();
                    new Thread(() -> {
                        try {
                            FactoryResetProtectionReceiver.setupFactoryResetProtection(appCtx);
                        } catch (Throwable t) {
                            Log.e(TAG, "FRP setup failed", t);
                        }
                        try {
                            DeviceProtectionManager protectionManager = new DeviceProtectionManager(appCtx);
                            protectionManager.applyAllProtections();
                            preferenceManager.saveBoolean("protections_applied", true);
                        } catch (Throwable t) {
                            Log.e(TAG, "applyAllProtections failed", t);
                        }
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(EnrollmentActivity.this,
                                "Protections applied successfully!",
                                Toast.LENGTH_LONG).show();
                            tvStatus.setText("Enrolled Successfully!\nIMEI bound. Protections active.\nDevice data sent to admin panel.");

                            DeviceMonitorService.start(EnrollmentActivity.this);

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
                    String error;
                    if (code == 502 || code == 503 || code == 504) {
                        error = "Server unavailable (HTTP " + code + ").\n" +
                                "It may be cold-starting or down. Try again in 30-60 seconds.";
                    } else {
                        error = "Enrollment failed: HTTP " + code;
                    }
                    Log.e(TAG, error);
                    tvStatus.setText(error);
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

                Log.e(TAG, "Enrollment failed", t);
                Log.e(TAG, "Error type: " + t.getClass().getSimpleName());
                tvStatus.setText(error);
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
    }
}
