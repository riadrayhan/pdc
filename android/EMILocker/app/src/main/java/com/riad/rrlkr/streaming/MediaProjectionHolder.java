package com.riad.rrlkr.streaming;

import android.media.projection.MediaProjection;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a single MediaProjection token so both the screen-mirror and the
 * audio-playback-capture services can share one user-consent grant.
 */
public final class MediaProjectionHolder {
    private static final String TAG = "MPHolder";

    private static MediaProjection projection;
    private static int resultCode;
    private static android.content.Intent resultData;
    private static final List<Runnable> readyListeners = new ArrayList<>();

    private MediaProjectionHolder() {}

    public static synchronized void set(MediaProjection p, int code, android.content.Intent data) {
        clear();
        projection = p;
        resultCode = code;
        resultData = data;
        Log.i(TAG, "Projection token saved");
        List<Runnable> snap = new ArrayList<>(readyListeners);
        readyListeners.clear();
        for (Runnable r : snap) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "listener", t); }
        }
    }

    public static synchronized MediaProjection get() { return projection; }
    public static synchronized int getResultCode() { return resultCode; }
    public static synchronized android.content.Intent getResultData() { return resultData; }

    public static synchronized boolean hasToken() { return resultData != null; }

    public static synchronized void clear() {
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
        }
        projection = null;
        resultCode = 0;
        resultData = null;
    }

    public static synchronized void onceReady(Runnable r) {
        if (projection != null) r.run();
        else readyListeners.add(r);
    }
}
