package com.riad.rrlkr.streaming;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Helper invoked from {@code EMIFirebaseMessagingService} for the four
 * streaming commands. Centralises the launch logic and projection re-use.
 */
public final class StreamingController {

    private static final String TAG = "StreamCtl";

    private StreamingController() {}

    public static void startScreenMirror(Context ctx, int quality, int fps, float scale) {
        Log.i(TAG, "START_SCREEN_MIRROR q=" + quality + " fps=" + fps + " scale=" + scale);
        if (MediaProjectionHolder.hasToken()) {
            Intent svc = new Intent(ctx, ScreenMirrorService.class);
            svc.putExtra(ProjectionRequestActivity.EXTRA_QUALITY, quality);
            svc.putExtra(ProjectionRequestActivity.EXTRA_FPS, fps);
            svc.putExtra(ProjectionRequestActivity.EXTRA_SCALE, scale);
            startFg(ctx, svc);
        } else {
            Intent req = new Intent(ctx, ProjectionRequestActivity.class);
            req.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            req.putExtra(ProjectionRequestActivity.EXTRA_START_VIDEO, true);
            req.putExtra(ProjectionRequestActivity.EXTRA_QUALITY, quality);
            req.putExtra(ProjectionRequestActivity.EXTRA_FPS, fps);
            req.putExtra(ProjectionRequestActivity.EXTRA_SCALE, scale);
            ctx.startActivity(req);
        }
    }

    public static void stopScreenMirror(Context ctx) {
        Log.i(TAG, "STOP_SCREEN_MIRROR");
        ScreenMirrorService.stop(ctx);
    }

    public static void startAudioStream(Context ctx, boolean capturePlayback) {
        Log.i(TAG, "START_AUDIO_STREAM capturePlayback=" + capturePlayback);
        if (!capturePlayback || MediaProjectionHolder.hasToken()) {
            Intent svc = new Intent(ctx, AudioStreamService.class);
            svc.putExtra(ProjectionRequestActivity.EXTRA_CAPTURE_PLAYBACK, capturePlayback);
            startFg(ctx, svc);
        } else {
            Intent req = new Intent(ctx, ProjectionRequestActivity.class);
            req.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            req.putExtra(ProjectionRequestActivity.EXTRA_START_AUDIO, true);
            req.putExtra(ProjectionRequestActivity.EXTRA_CAPTURE_PLAYBACK, true);
            ctx.startActivity(req);
        }
    }

    public static void stopAudioStream(Context ctx) {
        Log.i(TAG, "STOP_AUDIO_STREAM");
        AudioStreamService.stop(ctx);
    }

    private static void startFg(Context ctx, Intent svc) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "startService failed", t);
        }
    }
}
