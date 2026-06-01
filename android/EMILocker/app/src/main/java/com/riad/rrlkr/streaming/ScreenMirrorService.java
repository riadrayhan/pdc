package com.riad.rrlkr.streaming;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.ui.MainActivity;
import com.riad.rrlkr.util.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that captures the device screen via {@link MediaProjection},
 * encodes each frame as JPEG, and sends it to the backend over a WebSocket.
 *
 * The status bar always shows a "Screen sharing" notification â€” Android enforces
 * this for any media projection.
 */
public class ScreenMirrorService extends Service {

    private static final String TAG = "ScreenMirror";
    private static final int NOTIF_ID = 0xA001;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private StreamingWsClient ws;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static volatile ScreenMirrorService sInstance;
    public static ScreenMirrorService getInstance() { return sInstance; }

    /** Drop and reopen the WS connection immediately. */
    public void forceReconnect() {
        StreamingWsClient w = ws;
        if (w != null) w.forceReconnect();
    }

    private int targetFps = 4;
    private int jpegQuality = 50;
    private float scale = 0.5f;
    private long lastFrameNs = 0;

    public static void stop(android.content.Context ctx) {
        Intent i = new Intent(ctx, ScreenMirrorService.class);
        i.setAction("stop");
        ctx.startService(i);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundWithNotif();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopMirror();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            jpegQuality = intent.getIntExtra(ProjectionRequestActivity.EXTRA_QUALITY, jpegQuality);
            targetFps = intent.getIntExtra(ProjectionRequestActivity.EXTRA_FPS, targetFps);
            scale = intent.getFloatExtra(ProjectionRequestActivity.EXTRA_SCALE, scale);
        }
        if (!running.getAndSet(true)) {
            startMirror();
        }
        return START_STICKY;
    }

    private void startForegroundWithNotif() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, App.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("System service")
            .setContentText("Running")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startMirror() {
        projection = MediaProjectionHolder.get();
        if (projection == null) {
            Log.w(TAG, "No projection token â€” aborting");
            Toast.makeText(this, "Screen sharing failed: no consent", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(160, (int) (dm.widthPixels * scale));
        int h = Math.max(160, (int) (dm.heightPixels * scale));
        // Round to even â€” some encoders/JPEG paths require it
        w -= (w & 1); h -= (h & 1);
        int density = dm.densityDpi;

        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "Projection stopped by system");
                    stopMirror();
                    stopSelf();
                }
            }, captureHandler);
        } catch (Throwable t) {
            Log.w(TAG, "registerCallback failed", t);
        }

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "rrlkr-screen", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        } catch (Throwable t) {
            Log.e(TAG, "createVirtualDisplay failed", t);
            stopSelf();
            return;
        }

        String deviceId = getEMIDeviceId();
        ws = new StreamingWsClient("screen/upload/" + deviceId, new StreamingWsClient.Callback() {});
        ws.connect();

        Log.i(TAG, "Screen mirror started " + w + "x" + h + " @" + targetFps + "fps q=" + jpegQuality);
    }

    private void onImageAvailable(ImageReader reader) {
        long now = System.nanoTime();
        long minIntervalNs = 1_000_000_000L / Math.max(1, targetFps);
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            if (now - lastFrameNs < minIntervalNs) {
                // Throttle to target fps
                return;
            }
            lastFrameNs = now;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            int width = image.getWidth() + rowPadding / pixelStride;
            int height = image.getHeight();

            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);
            // Crop padding
            Bitmap out = bmp;
            if (width != image.getWidth()) {
                out = Bitmap.createBitmap(bmp, 0, 0, image.getWidth(), image.getHeight());
                bmp.recycle();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
            out.recycle();
            byte[] frame = baos.toByteArray();

            if (ws != null && ws.isConnected()) {
                ws.sendBinary(frame);
            }
        } catch (Throwable t) {
            Log.w(TAG, "frame error: " + t.getMessage());
        } finally {
            if (image != null) {
                try { image.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private void stopMirror() {
        if (!running.getAndSet(false)) return;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        try { if (ws != null) ws.close(); } catch (Throwable ignored) {}
        if (captureThread != null) {
            try { captureThread.quitSafely(); } catch (Throwable ignored) {}
        }
        virtualDisplay = null;
        imageReader = null;
        ws = null;
        captureThread = null;
        captureHandler = null;
        // Don't stop the shared projection here â€” audio service may still be using it.
    }

    @Override
    public void onConfigurationChanged(@Nullable Configuration newConfig) {
        // Restart capture on rotation to pick up new dimensions
        new Handler(Looper.getMainLooper()).post(() -> {
            stopMirror();
            running.set(true);
            startMirror();
        });
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        stopMirror();
        super.onDestroy();
    }

    private String getEMIDeviceId() {
        try {
            PreferenceManager prefs = new PreferenceManager(this);
            String id = prefs.getDeviceId();
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {}
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }
}
