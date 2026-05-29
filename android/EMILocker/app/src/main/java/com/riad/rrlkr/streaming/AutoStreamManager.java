package com.riad.rrlkr.streaming;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import com.riad.rrlkr.util.PreferenceManager;

/**
 * Single source of truth for "should this device currently be streaming".
 *
 * The desired state survives reboot, app kill, and network drops in shared
 * preferences. Anything that learns about the desired state (FCM commands,
 * the network callback, boot receiver, etc.) calls one of the methods here
 * and the manager takes care of starting / restarting the foreground service
 * â€” including re-prompting for MediaProjection consent when the cached token
 * has been revoked by the OS.
 */
public final class AutoStreamManager {

    private static final String TAG = "AutoStream";

    private static final String KEY_VIDEO_ON = "stream_video_desired";
    private static final String KEY_AUDIO_ON = "stream_audio_desired";
    private static final String KEY_VIDEO_QUALITY = "stream_video_quality";
    private static final String KEY_VIDEO_FPS = "stream_video_fps";
    private static final String KEY_VIDEO_SCALE = "stream_video_scale";
    private static final String KEY_AUDIO_PLAYBACK = "stream_audio_playback";

    private AutoStreamManager() {}

    // ----- desired-state persistence -----

    public static void setVideoDesired(Context ctx, boolean on, int quality, int fps, float scale) {
        PreferenceManager p = new PreferenceManager(ctx);
        p.saveBoolean(KEY_VIDEO_ON, on);
        if (on) {
            p.saveInt(KEY_VIDEO_QUALITY, quality);
            p.saveInt(KEY_VIDEO_FPS, fps);
            p.saveFloat(KEY_VIDEO_SCALE, scale);
        }
    }

    public static void setAudioDesired(Context ctx, boolean on, boolean capturePlayback) {
        PreferenceManager p = new PreferenceManager(ctx);
        p.saveBoolean(KEY_AUDIO_ON, on);
        if (on) p.saveBoolean(KEY_AUDIO_PLAYBACK, capturePlayback);
    }

    public static boolean isVideoDesired(Context ctx) {
        return new PreferenceManager(ctx).getBoolean(KEY_VIDEO_ON, false);
    }

    public static boolean isAudioDesired(Context ctx) {
        return new PreferenceManager(ctx).getBoolean(KEY_AUDIO_ON, false);
    }

    // ----- (re)start orchestration -----

    /**
     * Bring the live services in line with the desired state. Safe to call
     * from any thread and at any time; it's idempotent.
     */
    public static void apply(Context ctx) {
        PreferenceManager p = new PreferenceManager(ctx);
        boolean wantVideo = p.getBoolean(KEY_VIDEO_ON, false);
        boolean wantAudio = p.getBoolean(KEY_AUDIO_ON, false);

        if (!wantVideo && !wantAudio) return;

        int quality = p.getInt(KEY_VIDEO_QUALITY, 50);
        int fps = p.getInt(KEY_VIDEO_FPS, 4);
        float scale = p.getFloat(KEY_VIDEO_SCALE, 0.5f);
        boolean capturePlayback = p.getBoolean(KEY_AUDIO_PLAYBACK, true);

        if (wantVideo && ScreenMirrorService.getInstance() == null) {
            Log.i(TAG, "apply: (re)starting screen mirror");
            StreamingController.startScreenMirror(ctx, quality, fps, scale);
        } else if (wantVideo) {
            ScreenMirrorService.getInstance().forceReconnect();
        }

        if (wantAudio && AudioStreamService.getInstance() == null) {
            Log.i(TAG, "apply: (re)starting audio stream");
            StreamingController.startAudioStream(ctx, capturePlayback);
        } else if (wantAudio) {
            AudioStreamService.getInstance().forceReconnect();
        }
    }

    /** Used by the network callback when connectivity is restored. */
    public static void onNetworkAvailable(Context ctx) {
        Log.i(TAG, "Network available â€” reapplying desired stream state");
        apply(ctx);
    }

    /**
     * Register a process-lifetime {@link ConnectivityManager.NetworkCallback}
     * so we react instantly when Wi-Fi / cellular comes back. Call once from
     * {@code App.onCreate()}.
     */
    public static void registerNetworkCallback(Context appCtx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    onNetworkAvailable(appCtx);
                }
                @Override public void onCapabilitiesChanged(Network network,
                                                            NetworkCapabilities caps) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        onNetworkAvailable(appCtx);
                    }
                }
            });
            Log.i(TAG, "Network callback registered");
        } catch (Throwable t) {
            Log.w(TAG, "Could not register network callback: " + t.getMessage());
        }
    }

    /** Hard stop both streams. */
    public static void stopAll(Context ctx) {
        setVideoDesired(ctx, false, 0, 0, 0);
        setAudioDesired(ctx, false, false);
        StreamingController.stopScreenMirror(ctx);
        StreamingController.stopAudioStream(ctx);
    }
}
