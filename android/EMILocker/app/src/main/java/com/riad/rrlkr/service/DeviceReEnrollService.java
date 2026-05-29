package com.riad.rrlkr.service;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.network.DeviceEnrollRequestV2;
import com.riad.rrlkr.utils.DeviceFingerprint;
import com.riad.rrlkr.utils.PreferenceManager;

import org.json.JSONObject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Service to check if device needs re-enrollment after factory reset
 * This runs when network becomes available
 */
public class DeviceReEnrollService extends Service {
    
    private static final String TAG = "DeviceReEnrollService";
    
    private DeviceFingerprint fingerprint;
    private PreferenceManager preferenceManager;
    private ApiService apiService;
    
    @Override
    public void onCreate() {
        super.onCreate();
        fingerprint = new DeviceFingerprint(this);
        preferenceManager = PreferenceManager.getInstance(this);
        apiService = ApiClient.getClient().create(ApiService.class);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "CHECK_ENROLLMENT".equals(intent.getAction())) {
            checkDeviceEnrollment();
        }
        return START_NOT_STICKY;
    }
    
    /**
     * Check with server if this device was previously enrolled
     */
    private void checkDeviceEnrollment() {
        Log.d(TAG, "Checking device enrollment status");
        
        // Build enrollment check request with device fingerprint
        DeviceEnrollRequestV2 request = buildEnrollmentRequest();
        
        // Send to server
        apiService.checkDeviceStatus(request).enqueue(new Callback<DeviceStatusResponse>() {
            @Override
            public void onResponse(Call<DeviceStatusResponse> call, Response<DeviceStatusResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    DeviceStatusResponse status = response.body();
                    handleDeviceStatus(status);
                } else {
                    Log.e(TAG, "Server returned error: " + response.code());
                }
                stopSelf();
            }
            
            @Override
            public void onFailure(Call<DeviceStatusResponse> call, Throwable t) {
                Log.e(TAG, "Failed to check enrollment", t);
                stopSelf();
            }
        });
    }
    
    /**
     * Handle device status response from server
     */
    private void handleDeviceStatus(DeviceStatusResponse status) {
        Log.d(TAG, "Device status: known=" + status.isKnownDevice() + 
                   ", needsEnrollment=" + status.needsReEnrollment() +
                   ", shouldLock=" + status.shouldLock());
        
        if (status.isKnownDevice()) {
            if (status.needsReEnrollment()) {
                // Device was reset, needs to re-enroll
                Log.d(TAG, "Device needs re-enrollment");
                
                // If server provides APK URL, download and install
                if (status.getApkUrl() != null && !status.getApkUrl().isEmpty()) {
                    AppInstallManager installManager = new AppInstallManager(this);
                    installManager.downloadAndInstall(status.getApkUrl());
                }
                
                // Re-enroll with server
                reEnrollDevice();
            }
            
            if (status.shouldLock()) {
                // Device should be locked (overdue payment)
                Log.d(TAG, "Device should be locked - triggering lock");
                new com.riad.rrlkr.util.PreferenceManager(this).saveBoolean("admin_lock_command", true);
                LockManager.showLockScreen(this, "Device locked. Please contact your administrator.", null);
            }
        }
    }
    
    /**
     * Re-enroll device with server
     */
    private void reEnrollDevice() {
        DeviceEnrollRequestV2 request = buildEnrollmentRequest();
        
        apiService.enrollDevice(request).enqueue(new Callback<EnrollResponse>() {
            @Override
            public void onResponse(Call<EnrollResponse> call, Response<EnrollResponse> response) {
                if (response.isSuccessful()) {
                    Log.d(TAG, "Device re-enrolled successfully");
                    preferenceManager.setEnrolled(true);
                    
                    // Store server token
                    if (response.body() != null && response.body().getDeviceToken() != null) {
                        preferenceManager.saveString("device_token", response.body().getDeviceToken());
                    }
                }
            }
            
            @Override
            public void onFailure(Call<EnrollResponse> call, Throwable t) {
                Log.e(TAG, "Re-enrollment failed", t);
            }
        });
    }
    
    /**
     * Build enrollment request with full device fingerprint
     */
    private DeviceEnrollRequestV2 buildEnrollmentRequest() {
        DeviceEnrollRequestV2 request = new DeviceEnrollRequestV2();
        
        // Hardware identifiers
        request.setImei(fingerprint.getIMEI());
        request.setImei2(fingerprint.getIMEI2());
        request.setSerialNumber(fingerprint.getSerialNumber());
        request.setPersistentDeviceId(fingerprint.getPersistentDeviceId());
        request.setAndroidId(fingerprint.getAndroidId());
        
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
            request.setAppVersion("unknown");
        }
        
        return request;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    // Response classes
    public static class DeviceStatusResponse {
        private boolean knownDevice;
        private boolean needsReEnrollment;
        private boolean shouldLock;
        private String apkUrl;
        private String lockMessage;
        private boolean found;
        private String status;
        @com.google.gson.annotations.SerializedName("device_id")
        private String deviceId;
        
        public boolean isKnownDevice() { return knownDevice; }
        public boolean needsReEnrollment() { return needsReEnrollment; }
        public boolean shouldLock() { return shouldLock; }
        public String getApkUrl() { return apkUrl; }
        public String getLockMessage() { return lockMessage; }
        public boolean isFound() { return found || knownDevice; }
        public String getStatus() { return status; }
        public String getDeviceId() { return deviceId; }
    }
    
    public static class EnrollResponse {
        @com.google.gson.annotations.SerializedName("device_token")
        private String deviceToken;
        
        @com.google.gson.annotations.SerializedName("device_id")
        private String deviceId;
        
        @com.google.gson.annotations.SerializedName("success")
        private boolean success;
        
        public String getDeviceToken() { return deviceToken; }
        public String getDeviceId() { return deviceId; }
        public boolean isSuccess() { return success; }
    }
}
