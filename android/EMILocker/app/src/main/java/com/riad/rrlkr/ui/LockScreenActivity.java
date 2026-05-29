package com.riad.rrlkr.ui;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.riad.rrlkr.R;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.models.CommandAck;
import com.riad.rrlkr.network.models.CommandResponse;
import com.riad.rrlkr.service.DeviceProtectionManager;
import com.riad.rrlkr.service.SamsungProtectionManager;
import com.riad.rrlkr.util.DeviceProtectedPrefs;
import com.riad.rrlkr.util.PreferenceManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Lock Screen Activity
 * Displays when device is locked by administrator
 */
public class LockScreenActivity extends AppCompatActivity {
    
    private static final String TAG = "LockScreenActivity";
    
    public static final String ACTION_UNLOCK = "com.riad.rrlkr.ACTION_UNLOCK";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_CONTACT = "extra_contact";
    
    private TextView tvMessage;
    private TextView tvContact;
    
    private String contactNumber;
    private PreferenceManager preferenceManager;
    
    // Camera preview for face detection (prank - does not send to server)
    private TextureView cameraPreview;
    private TextView tvFaceStatus;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private boolean cameraOpened = false;
    
    // Polling for unlock commands
    private Handler pollingHandler;
    private static final long POLLING_INTERVAL = 3000; // 3 seconds
    private ApiService apiService;
    private boolean isUnlocking = false; // Flag to prevent re-launch during unlock
    private Handler relaunchHandler; // Single handler for relaunch (prevents storm)
    private Runnable pendingRelaunch; // Track pending relaunch to cancel it
    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            checkPendingCommands();
            pollingHandler.postDelayed(this, POLLING_INTERVAL);
        }
    };
    
    // Broadcast receiver for unlock action
    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_UNLOCK.equals(intent.getAction())) {
                // Unlock received, close this activity
                finish();
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make activity show on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        
        // Full screen flags
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        
        // Hide navigation bar
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        
        setContentView(R.layout.activity_lock_screen);
        
        preferenceManager = new PreferenceManager(this);
        
        // Initialize views
        tvMessage = findViewById(R.id.tv_lock_message);
        tvContact = findViewById(R.id.tv_contact_info);
        cameraPreview = findViewById(R.id.camera_preview);
        tvFaceStatus = findViewById(R.id.tv_face_status);
        
        // Get message and contact from intent or preferences
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        contactNumber = getIntent().getStringExtra(EXTRA_CONTACT);
        
        if (message == null || message.isEmpty()) {
            message = preferenceManager.getLockMessage();
        }
        if (contactNumber == null || contactNumber.isEmpty()) {
            contactNumber = preferenceManager.getContactNumber();
        }
        
        // WARNING text is hardcoded in XML â€” never overwrite it
        // Server message goes to contact_info field instead
        if (message != null && !message.isEmpty() 
                && !message.equals("Device locked by administrator.")) {
            tvContact.setText(message);
            tvContact.setVisibility(View.VISIBLE);
        }
        
        // Contact info is already hardcoded in XML as emergency number
        // Only show tvContact if server sent a custom message above
        
        // Register unlock receiver (NOT_EXPORTED â€” only our app can send it)
        IntentFilter filter = new IntentFilter(ACTION_UNLOCK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unlockReceiver, filter);
        }
        
        // Start lock task mode if device owner
        startLockTaskModeIfAvailable();
        
        // Apply extra lock-mode hardening (factory reset / reboot / recovery blocks)
        // Run on a background thread \u2014 this calls many DPM APIs and would
        // otherwise stall the lock screen activity creation.
        if (EMIDeviceAdminReceiver.isDeviceOwner(this)) {
            final DeviceProtectionManager lockProtection = new DeviceProtectionManager(getApplicationContext());
            new Thread(() -> {
                try {
                    lockProtection.applyLockModeHardening();
                } catch (Throwable t) {
                    Log.e(TAG, "applyLockModeHardening failed", t);
                }
            }, "LockHardening").start();
        }
        
        // Initialize API service and start polling for unlock commands
        apiService = ApiClient.getClient().create(ApiService.class);
        pollingHandler = new Handler(Looper.getMainLooper());
        relaunchHandler = new Handler(Looper.getMainLooper());
        pollingHandler.post(pollingRunnable);
        Log.i(TAG, "Started polling for unlock commands");
        
        // Start camera preview with face detection (prank system)
        setupCameraPreview();
    }
    
    // ==================== Camera Preview (Prank Face Detection) ====================
    
    private void setupCameraPreview() {
        if (cameraPreview.isAvailable()) {
            openFrontCamera();
        } else {
            cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    openFrontCamera();
                }
                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    closeCamera();
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });
        }
    }
    
    private void openFrontCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted, skipping preview");
            if (tvFaceStatus != null) tvFaceStatus.setText("Camera unavailable");
            return;
        }
        
        // Ensure camera is enabled via DPM
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = EMIDeviceAdminReceiver.getComponentName(this);
                dpm.setCameraDisabled(admin, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enable camera via DPM: " + e.getMessage());
        }
        
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (cameraManager == null) return;
        
        cameraThread = new HandlerThread("CameraPreviewThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        
        try {
            String frontCameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id;
                    break;
                }
            }
            if (frontCameraId == null) {
                // fallback to any camera
                String[] ids = cameraManager.getCameraIdList();
                if (ids.length > 0) frontCameraId = ids[0];
            }
            if (frontCameraId == null) {
                Log.w(TAG, "No camera found");
                return;
            }
            
            cameraManager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    cameraOpened = true;
                    startCameraPreview();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    cameraOpened = false;
                }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    cameraOpened = false;
                    Log.e(TAG, "Camera open error: " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Error opening camera: " + e.getMessage());
        }
    }
    
    private void startCameraPreview() {
        if (cameraDevice == null || !cameraPreview.isAvailable()) return;
        
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(200, 200);
            Surface surface = new Surface(texture);
            
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE);
            
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            try {
                                session.setRepeatingRequest(previewRequestBuilder.build(),
                                        faceDetectionCallback, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error starting preview: " + e.getMessage());
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera preview configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating camera preview session: " + e.getMessage());
        }
    }
    
    private final CameraCaptureSession.CaptureCallback faceDetectionCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
                    if (faces != null && faces.length > 0) {
                        runOnUiThread(() -> {
                            if (tvFaceStatus != null) {
                                tvFaceStatus.setText("âœ“ Face Detected - Image Captured");
                                tvFaceStatus.setTextColor(getResources().getColor(R.color.green));
                            }
                        });
                        // Prank: pretend image captured, do NOT send anywhere
                        Log.d(TAG, "Face detected (prank capture) - faces: " + faces.length);
                    } else {
                        runOnUiThread(() -> {
                            if (tvFaceStatus != null) {
                                tvFaceStatus.setText("Scanning...");
                                tvFaceStatus.setTextColor(getResources().getColor(R.color.white));
                            }
                        });
                    }
                }
            };
    
    private void closeCamera() {
        try {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (cameraThread != null) {
                cameraThread.quitSafely();
                try { cameraThread.join(2000); } catch (InterruptedException ignored) {}
                cameraThread = null;
                cameraHandler = null;
            }
            cameraOpened = false;
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera: " + e.getMessage());
        }
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Re-apply immersive mode
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Close camera
        closeCamera();
        
        // Stop polling
        if (pollingHandler != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
            Log.i(TAG, "Stopped polling for unlock commands");
        }
        
        // Cancel pending relaunch
        if (relaunchHandler != null && pendingRelaunch != null) {
            relaunchHandler.removeCallbacks(pendingRelaunch);
        }
        
        try {
            unregisterReceiver(unlockReceiver);
        } catch (Exception e) {
            // Ignore
        }
        
        // IMPORTANT: Do NOT call stopLockTask() here!
        // Lock task mode must persist even after the lock screen is dismissed.
        // LockTaskBootActivity (which is below us in the same task) will keep
        // lock task mode active with normal features (power menu blocked).
        // Only explicitly stop lock task when DISABLE_APP or UNINSTALL_APP is received.
        Log.i(TAG, "LockScreenActivity destroyed â€” lock task mode preserved via LockTaskBootActivity");
    }
    
    @Override
    public void onBackPressed() {
        // Prevent back button from closing the lock screen
        // Do nothing
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Block home, recent apps, power, and other system keys
        if (keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_SEARCH ||
            keyCode == KeyEvent.KEYCODE_SETTINGS) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Intercept all system key events
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        scheduleRelaunchIfLocked(500);
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        scheduleRelaunchIfLocked(1000);
    }
    
    /**
     * Single guarded relaunch controller. Cancels any pending relaunch first,
     * then schedules one new relaunch. Prevents activity storms and ANR loops.
     */
    private void scheduleRelaunchIfLocked(long delayMs) {
        if (isUnlocking || isFinishing()) return;
        
        // Cancel any previously pending relaunch
        if (pendingRelaunch != null && relaunchHandler != null) {
            relaunchHandler.removeCallbacks(pendingRelaunch);
        }
        
        pendingRelaunch = () -> {
            if (isUnlocking || isFinishing()) return;
            
            // Double-check lock state from device-protected prefs (reliable)
            boolean stillLocked = false;
            try {
                stillLocked = preferenceManager.isDeviceLocked();
            } catch (Exception e) { /* ignore */ }
            if (!stillLocked) {
                try {
                    DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(LockScreenActivity.this);
                    stillLocked = dpPrefs.isDeviceLocked();
                } catch (Exception e) { /* ignore */ }
            }
            
            if (stillLocked) {
                Log.w(TAG, "Lock screen lost focus while device still locked â€” re-launching");
                try {
                    Intent intent = new Intent(LockScreenActivity.this, LockScreenActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to relaunch lock screen: " + e.getMessage());
                }
            }
        };
        
        if (relaunchHandler != null) {
            relaunchHandler.postDelayed(pendingRelaunch, delayMs);
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Re-enter lock task mode when relaunched
        if (!isUnlocking && preferenceManager.isDeviceLocked()) {
            startLockTaskModeIfAvailable();
        }
    }
    
    private void startLockTaskModeIfAvailable() {
        if (EMIDeviceAdminReceiver.isDeviceOwner(this)) {
            boolean isSamsung = Build.MANUFACTURER != null &&
                    Build.MANUFACTURER.toLowerCase().contains("samsung");
            try {
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                protectionManager.disableStatusBarExpansion();
                
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                ComponentName admin = EMIDeviceAdminReceiver.getComponentName(this);
                
                // Whitelist only our app for full kiosk mode during lock screen
                // Samsung: Also whitelist com.android.systemui so Samsung's SystemUI
                // can render status bar elements without crashing
                if (isSamsung) {
                    java.util.List<String> lockPkgList = new java.util.ArrayList<>();
                    lockPkgList.add(getPackageName());
                    lockPkgList.add("com.android.systemui");
                    try {
                        getPackageManager().getPackageInfo("com.samsung.android.incallui", 0);
                        lockPkgList.add("com.samsung.android.incallui");
                    } catch (Exception ignored) {}
                    dpm.setLockTaskPackages(admin, lockPkgList.toArray(new String[0]));
                } else {
                    dpm.setLockTaskPackages(admin, new String[]{ getPackageName() });
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Samsung: Use SYSTEM_INFO instead of NONE â€” Samsung SystemUI crashes with NONE
                    if (isSamsung) {
                        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO);
                    } else {
                        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                    }
                }
                
                startLockTask();
                Log.i(TAG, "Lock Task Mode STARTED (FULL KIOSK) - Everything BLOCKED");
            } catch (Exception e) {
                Log.e(TAG, "Lock task error: " + e.getMessage(), e);
            }
        } else {
            Log.w(TAG, "Not device owner - cannot start lock task mode");
        }
    }
    
    /**
     * Transition lock task from full kiosk mode (lock screen) back to normal mode.
     * Does NOT stop lock task â€” only changes features so user can use the phone normally
     * while power menu remains blocked.
     * 
     * Only call stopLockTask() for DISABLE_APP or UNINSTALL_APP commands.
     */
    private void transitionToNormalLockTaskMode() {
        try {
            if (EMIDeviceAdminReceiver.isDeviceOwner(this)) {
                boolean isSamsung = Build.MANUFACTURER != null &&
                        Build.MANUFACTURER.toLowerCase().contains("samsung");

                // Re-enable status bar
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                protectionManager.enableStatusBar();
                
                // Remove lock-mode hardening restrictions
                protectionManager.removeLockModeHardening();

                if (isSamsung) {
                    // Samsung: STOP lock task mode entirely when unlocking.
                    // Samsung uses Knox APIs for power blocking in normal mode,
                    // NOT lock task. Re-apply Knox protections instead.
                    try {
                        stopLockTask();
                        Log.i(TAG, "Samsung: Lock task STOPPED â€” transitioning to Knox-only mode");
                    } catch (Exception e) {
                        Log.w(TAG, "Samsung: stopLockTask failed: " + e.getMessage());
                    }
                    // Re-configure lock task packages for future kiosk use
                    protectionManager.disableRebootPowerOff();
                    // Re-apply Knox power blocking
                    SamsungProtectionManager samsungMgr = new SamsungProtectionManager(this);
                    samsungMgr.blockPowerOffViaKnox();
                } else {
                    // Non-Samsung: Restore normal lock task mode (all apps whitelisted, power menu BLOCKED)
                    protectionManager.disableRebootPowerOff();
                    // DO NOT call stopLockTask() â€” lock task mode must persist!
                }
                
                Log.i(TAG, "Transitioned to NORMAL mode â€” power menu still BLOCKED");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error transitioning lock task mode", e);
        }
    }
    
    /**
     * Fully stop lock task mode â€” only for DISABLE_APP and UNINSTALL_APP.
     */
    private void fullyStopLockTaskMode() {
        try {
            if (EMIDeviceAdminReceiver.isDeviceOwner(this)) {
                stopLockTask();
                DeviceProtectionManager protectionManager = new DeviceProtectionManager(this);
                protectionManager.enableStatusBar();
                protectionManager.removeLockModeHardening();
                Log.i(TAG, "Lock Task Mode FULLY STOPPED for app removal");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping lock task mode", e);
        }
    }
    
    private void checkPendingCommands() {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            // Fallback: restore from device-protected storage
            try {
                DeviceProtectedPrefs dpPrefs = new DeviceProtectedPrefs(this);
                deviceId = dpPrefs.getDeviceId();
                if (deviceId != null && !deviceId.isEmpty()) {
                    preferenceManager.setDeviceId(deviceId);
                    Log.i(TAG, "Restored deviceId from device-protected storage for lock screen polling");
                }
            } catch (Exception e) { /* ignore */ }
        }
        
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "No device ID found, skipping pending commands check");
            return;
        }
        
        Log.d(TAG, "Checking pending commands for device: " + deviceId);
        
        apiService.getPendingCommands(deviceId).enqueue(new Callback<List<CommandResponse>>() {
            @Override
            public void onResponse(Call<List<CommandResponse>> call, Response<List<CommandResponse>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<CommandResponse> commands = response.body();
                    Log.d(TAG, "Received " + commands.size() + " pending commands");
                    if (!commands.isEmpty()) {
                        processCommands(commands);
                    }
                } else {
                    Log.e(TAG, "Failed to get pending commands: " + response.code());
                }
            }
            
            @Override
            public void onFailure(Call<List<CommandResponse>> call, Throwable t) {
                Log.e(TAG, "Error checking pending commands: " + t.getMessage());
            }
        });
    }
    
    private void processCommands(List<CommandResponse> commands) {
        for (CommandResponse cmd : commands) {
            String type = cmd.getCommandType();
            Log.d(TAG, "Processing command: " + type + " id: " + cmd.getId());
            
            if ("UNLOCK".equalsIgnoreCase(type) || "unlock".equals(type)
                    || "DISABLE_APP".equalsIgnoreCase(type) || "disable_app".equals(type)
                    || "UNINSTALL_APP".equalsIgnoreCase(type) || "uninstall_app".equals(type)) {
                Log.i(TAG, type + " command received - unlocking device");
                
                // Set unlocking flag FIRST to prevent onPause re-launch
                isUnlocking = true;
                
                // Save unlocked state synchronously
                preferenceManager.setDeviceLocked(false);
                
                // Stop polling immediately
                if (pollingHandler != null) {
                    pollingHandler.removeCallbacks(pollingRunnable);
                }
                
                // If disable_app or uninstall_app: remove all protections, uninstall permanently
                if ("DISABLE_APP".equalsIgnoreCase(type) || "disable_app".equals(type)
                        || "UNINSTALL_APP".equalsIgnoreCase(type) || "uninstall_app".equals(type)) {
                    // Mark as disabled FIRST so app doesn't re-apply on restart
                    preferenceManager.setAppDisabled(true);
                    preferenceManager.setProtectionsApplied(false);
                    
                    DeviceProtectionManager protectionManager = new DeviceProtectionManager(LockScreenActivity.this);
                    protectionManager.removeAllProtections();
                    protectionManager.unhideAppInLauncher();
                    protectionManager.enableStatusBar();
                    
                    // FULLY stop lock task mode for app removal
                    fullyStopLockTaskMode();
                    
                    // Always clear device owner and permanently uninstall the app
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        protectionManager.clearDeviceOwnerAndUninstall();
                    }, 2000);
                } else {
                    // UNLOCK only: transition to normal lock task mode (power menu stays blocked)
                    transitionToNormalLockTaskMode();
                }
                
                // Acknowledge the command
                acknowledgeCommand(cmd.getId(), "executed");
                
                // Close lock screen
                Log.i(TAG, "Finishing LockScreenActivity");
                finish();
                break;
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
}
