package com.riad.rrlkr.streaming;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.ui.MainActivity;
import com.riad.rrlkr.util.PreferenceManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that captures live audio (microphone + optional system
 * playback) and streams raw 16-bit PCM frames to the backend WebSocket. The
 * admin browser plays them in real time via the Web Audio API.
 *
 * Two AudioRecord instances are read in parallel and summed sample-by-sample
 * so the admin hears both the device user and what the device is playing
 * (Google Meet other-side voice, music, etc., provided that app didn't set
 * allowAudioPlaybackCapture=false).
 */
public class AudioStreamService extends Service {

    private static final String TAG = "AudioStream";
    private static final int NOTIF_ID = 0xA002;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO;
    private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private StreamingWsClient ws;
    private Thread micThread;
    private Thread playbackThread;
    private AudioRecord micRecord;
    private AudioRecord playbackRecord;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean capturePlayback = true;

    private static volatile AudioStreamService sInstance;
    public static AudioStreamService getInstance() { return sInstance; }

    /** Drop and reopen the WS connection immediately. */
    public void forceReconnect() {
        StreamingWsClient w = ws;
        if (w != null) w.forceReconnect();
    }

    public static void stop(android.content.Context ctx) {
        Intent i = new Intent(ctx, AudioStreamService.class);
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
            stopStream();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            capturePlayback = intent.getBooleanExtra(
                ProjectionRequestActivity.EXTRA_CAPTURE_PLAYBACK, true);
        }
        if (!running.getAndSet(true)) {
            startStream();
        }
        return START_STICKY;
    }

    private void startForegroundWithNotif() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, App.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("Microphone is active")
            .setContentText("Live audio is being shared with the device administrator.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build();

        int types = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= 29 && capturePlayback) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        if (Build.VERSION.SDK_INT >= 29 && types != 0) {
            try {
                startForeground(NOTIF_ID, n, types);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "startForeground typed failed: " + t.getMessage());
            }
        }
        startForeground(NOTIF_ID, n);
    }

    @SuppressLint("MissingPermission")
    private void startStream() {
        String deviceId = getEMIDeviceId();
        ws = new StreamingWsClient("screen/audio/upload/" + deviceId,
            new StreamingWsClient.Callback() {
                @Override public void onOpen() {
                    // Announce format so the viewer can decode correctly
                    ws.sendText("{\"sample_rate\":" + SAMPLE_RATE
                        + ",\"channels\":1,\"bits\":16}");
                }
            });
        ws.connect();

        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, FORMAT);
        if (bufSize <= 0) bufSize = 4096;
        bufSize = Math.max(bufSize, 4096);
        // Stream in small fixed ~40ms chunks for low-latency realtime audio.
        // 16kHz mono PCM16 -> 16000 * 0.04 * 2 = 1280 bytes per chunk.
        final int frameBytes = (SAMPLE_RATE / 25) * 2; // 40ms

        // --- Microphone source ---
        try {
            micRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CFG, FORMAT, bufSize);
            if (micRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                // Fallback to plain MIC
                micRecord.release();
                micRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CFG, FORMAT, bufSize);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not create mic AudioRecord", t);
            micRecord = null;
        }

        // --- System playback capture (Android 10+, needs MediaProjection) ---
        MediaProjection projection = MediaProjectionHolder.get();
        if (capturePlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
            try {
                playbackRecord = buildPlaybackCaptureRecord(projection, bufSize);
            } catch (Throwable t) {
                Log.w(TAG, "Playback capture unavailable: " + t.getMessage());
                playbackRecord = null;
            }
        }

        if (micRecord != null && micRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            micRecord.startRecording();
        }
        if (playbackRecord != null && playbackRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            playbackRecord.startRecording();
        }

        // Mixed streaming loop â€” run mic and playback on threads, mix in mic thread.
        micThread = new Thread(() -> readAndStream(frameBytes), "audio-mic");
        micThread.start();
        Log.i(TAG, "Audio stream started (mic=" + (micRecord != null)
            + ", playback=" + (playbackRecord != null) + ")");
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private AudioRecord buildPlaybackCaptureRecord(MediaProjection projection, int bufSize) {
        AudioPlaybackCaptureConfiguration.Builder b =
            new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        // VOICE_COMMUNICATION usage is restricted by the framework on most builds,
        // so we deliberately don't add it â€” playback from VoIP apps like Meet may
        // still be picked up via USAGE_MEDIA on devices where the app routes it
        // through the media stream.
        AudioFormat fmt = new AudioFormat.Builder()
            .setEncoding(FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CFG)
            .build();
        return new AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(b.build())
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(Math.max(bufSize, 4096))
            .build();
    }

    private void readAndStream(int frameBytes) {
        byte[] micBuf = new byte[frameBytes];
        byte[] playBuf = new byte[frameBytes];
        byte[] outBuf = new byte[frameBytes];
        while (running.get()) {
            int micRead = 0;
            int playRead = 0;
            if (micRecord != null) {
                micRead = micRecord.read(micBuf, 0, frameBytes);
                if (micRead < 0) micRead = 0;
            }
            if (playbackRecord != null) {
                playRead = playbackRecord.read(playBuf, 0, frameBytes);
                if (playRead < 0) playRead = 0;
            }
            int n = Math.max(micRead, playRead);
            if (n <= 0) {
                // Both empty â€” yield briefly
                try { Thread.sleep(5); } catch (InterruptedException ignored) { return; }
                continue;
            }
            if (micRead > 0 && playRead > 0) {
                mixPcm16(micBuf, playBuf, outBuf, Math.min(micRead, playRead));
                if (ws != null && ws.isConnected()) {
                    ws.sendBinary(trim(outBuf, Math.min(micRead, playRead)));
                }
            } else if (micRead > 0) {
                if (ws != null && ws.isConnected()) {
                    ws.sendBinary(trim(micBuf, micRead));
                }
            } else {
                if (ws != null && ws.isConnected()) {
                    ws.sendBinary(trim(playBuf, playRead));
                }
            }
        }
    }

    /** Sum two PCM16 buffers sample-by-sample with clipping. */
    private static void mixPcm16(byte[] a, byte[] b, byte[] out, int byteLen) {
        for (int i = 0; i + 1 < byteLen; i += 2) {
            int s1 = (short) ((a[i] & 0xFF) | (a[i + 1] << 8));
            int s2 = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int sum = s1 + s2;
            if (sum > 32767) sum = 32767;
            else if (sum < -32768) sum = -32768;
            out[i] = (byte) (sum & 0xFF);
            out[i + 1] = (byte) ((sum >> 8) & 0xFF);
        }
    }

    private static byte[] trim(byte[] src, int n) {
        if (n == src.length) return src.clone();
        byte[] dst = new byte[n];
        System.arraycopy(src, 0, dst, 0, n);
        return dst;
    }

    private void stopStream() {
        if (!running.getAndSet(false)) return;
        try { if (micRecord != null) { micRecord.stop(); micRecord.release(); } } catch (Throwable ignored) {}
        try { if (playbackRecord != null) { playbackRecord.stop(); playbackRecord.release(); } } catch (Throwable ignored) {}
        try { if (ws != null) ws.close(); } catch (Throwable ignored) {}
        micRecord = null;
        playbackRecord = null;
        ws = null;
        try { if (micThread != null) micThread.interrupt(); } catch (Throwable ignored) {}
        micThread = null;
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        stopStream();
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
