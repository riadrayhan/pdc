package com.riad.rrlkr.streaming;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * Transparent activity that asks the user / system for a MediaProjection token
 * (required by Android for screen capture and AudioPlaybackCapture). Once we
 * have a token we cache it in {@link MediaProjectionHolder} and start whatever
 * service originally requested the projection.
 *
 * Launch parameters:
 *   EXTRA_START_VIDEO  â€“ true to start {@link ScreenMirrorService} after consent
 *   EXTRA_START_AUDIO  â€“ true to start {@link AudioStreamService} after consent
 *   plus any START_* extras pass-through (quality / fps / scale)
 */
public class ProjectionRequestActivity extends Activity {

    private static final String TAG = "ProjReqAct";
    private static final int REQUEST_CODE = 9001;

    public static final String EXTRA_START_VIDEO = "start_video";
    public static final String EXTRA_START_AUDIO = "start_audio";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_SCALE = "scale";
    public static final String EXTRA_CAPTURE_PLAYBACK = "capture_playback";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setLayout(1, 1);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

        if (MediaProjectionHolder.hasToken()) {
            // Already granted earlier in this session â€” reuse it.
            startRequestedServices();
            finish();
            return;
        }

        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent intent = mgr.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_CODE);
        } catch (Throwable t) {
            Log.e(TAG, "createScreenCaptureIntent failed", t);
            Toast.makeText(this, "Screen capture not supported", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE) return;
        if (resultCode == RESULT_OK && data != null) {
            try {
                MediaProjectionManager mgr = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
                MediaProjection projection = mgr.getMediaProjection(resultCode, data);
                MediaProjectionHolder.set(projection, resultCode, data);
                Log.i(TAG, "Consent granted, starting services");
                startRequestedServices();
            } catch (Throwable t) {
                Log.e(TAG, "getMediaProjection failed", t);
                Toast.makeText(this, "Screen mirror error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Log.w(TAG, "User denied screen capture consent");
            Toast.makeText(this, "Screen sharing was declined", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void startRequestedServices() {
        Intent in = getIntent();
        if (in == null) return;
        boolean wantVideo = in.getBooleanExtra(EXTRA_START_VIDEO, false);
        boolean wantAudio = in.getBooleanExtra(EXTRA_START_AUDIO, false);

        if (wantVideo) {
            Intent svc = new Intent(this, ScreenMirrorService.class);
            svc.putExtra(EXTRA_QUALITY, in.getIntExtra(EXTRA_QUALITY, 50));
            svc.putExtra(EXTRA_FPS, in.getIntExtra(EXTRA_FPS, 4));
            svc.putExtra(EXTRA_SCALE, in.getFloatExtra(EXTRA_SCALE, 0.5f));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
        if (wantAudio) {
            Intent svc = new Intent(this, AudioStreamService.class);
            svc.putExtra(EXTRA_CAPTURE_PLAYBACK,
                in.getBooleanExtra(EXTRA_CAPTURE_PLAYBACK, true));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        }
    }
}
