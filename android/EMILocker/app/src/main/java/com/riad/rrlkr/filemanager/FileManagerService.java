package com.riad.rrlkr.filemanager;

import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.riad.rrlkr.App;
import com.riad.rrlkr.R;
import com.riad.rrlkr.streaming.StreamingWsClient;
import com.riad.rrlkr.util.PreferenceManager;
import com.riad.rrlkr.util.DeviceUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that exposes the device's full file system (internal
 * storage + SD card) to the admin dashboard via a WebSocket bridge.
 *
 * Started via FCM command {@code START_FILE_MANAGER}; stopped via
 * {@code STOP_FILE_MANAGER}. All admin requests come in as JSON text frames
 * over {@code /files/device/{deviceId}} and responses (listings, errors,
 * download chunks) go out as JSON text frames.
 */
public class FileManagerService extends Service {

    private static final String TAG = "FileManagerSvc";
    private static final int NOTIF_ID = 0xA005;
    private static final int CHUNK_SIZE = 64 * 1024; // 64 KB raw -> ~88 KB base64

    private StreamingWsClient ws;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService io;

    public static void start(android.content.Context ctx) {
        Intent i = new Intent(ctx, FileManagerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }

    public static void stop(android.content.Context ctx) {
        Intent i = new Intent(ctx, FileManagerService.class);
        i.setAction("stop");
        try { ctx.startService(i); } catch (Throwable ignored) {}
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        io = Executors.newSingleThreadExecutor();
        startForegroundWithNotif();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopWs();
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureStoragePermissions();
        if (!running.getAndSet(true)) startWs();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWs();
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    private void startForegroundWithNotif() {
        Notification n = new NotificationCompat.Builder(this, App.CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("Remote file access active")
            .setContentText("Administrator can browse files on this device.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startWs() {
        String deviceId = new PreferenceManager(this).getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            // Fall back to IMEI-based id so unenrolled devices still work
            deviceId = DeviceUtils.getIMEI(this);
        }
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "No device id available â€” aborting");
            stopSelf();
            return;
        }
        ws = new StreamingWsClient("files/device/" + deviceId, new StreamingWsClient.Callback() {
            @Override public void onTextMessage(String text) {
                ExecutorService ex = io;
                if (ex != null && !ex.isShutdown()) ex.execute(() -> handleRequest(text));
            }
        });
        ws.connect();
        Log.i(TAG, "FileManager WS connecting device=" + deviceId);
    }

    private void stopWs() {
        if (ws != null) {
            try { ws.close(); } catch (Throwable ignored) {}
            ws = null;
        }
        running.set(false);
    }

    /**
     * Best-effort grant of every storage / media runtime permission via Device
     * Owner privilege, then escalate to MANAGE_EXTERNAL_STORAGE (all-files
     * access) by launching the system settings panel if we are not yet a
     * storage manager. On Android 14+ the granular READ_MEDIA_* permissions
     * are required even when MANAGE_EXTERNAL_STORAGE is held.
     */
    private void ensureStoragePermissions() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin =
                    com.riad.rrlkr.admin.EMIDeviceAdminReceiver.getComponentName(this);
                String[] perms;
                if (Build.VERSION.SDK_INT >= 33) {
                    perms = new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.READ_MEDIA_AUDIO",
                        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    };
                } else {
                    perms = new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    };
                }
                for (String p : perms) {
                    try {
                        dpm.setPermissionGrantState(admin, getPackageName(), p,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    } catch (Throwable t) {
                        Log.w(TAG, "grant " + p + " failed: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureStoragePermissions DPM step failed: " + t.getMessage());
        }

        // MANAGE_EXTERNAL_STORAGE (all-files access) is an App-Op, not a
        // runtime permission â€” it cannot be granted silently even by a Device
        // Owner. If not held, open the dedicated settings panel so the admin
        // can flip the switch with a single tap.
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.i(TAG, "Opened MANAGE_EXTERNAL_STORAGE settings panel");
            } catch (Throwable t) {
                // Fallback to the global manage-all-files screen
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {
                    Log.w(TAG, "Could not open all-files-access settings: " + t.getMessage());
                }
            }
        }
    }

    private void handleRequest(String text) {
        String reqId = "";
        try {
            JSONObject req = new JSONObject(text);
            reqId = req.optString("req_id", "");
            String action = req.optString("action", "");
            switch (action) {
                case "roots":
                    sendRoots(reqId);
                    break;
                case "list":
                    sendList(reqId, req.optString("path", ""));
                    break;
                case "download":
                    sendFile(reqId, req.optString("path", ""));
                    break;
                case "delete":
                    sendDelete(reqId, req.optString("path", ""));
                    break;
                default:
                    sendError(reqId, "unknown_action:" + action);
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleRequest failed", t);
            sendError(reqId, t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private void sendRoots(String reqId) throws Exception {
        JSONArray arr = new JSONArray();
        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) arr.put(rootJson("Internal Storage", internal, false));

        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (sm != null && Build.VERSION.SDK_INT >= 24) {
            try {
                List<StorageVolume> vols = sm.getStorageVolumes();
                for (StorageVolume v : vols) {
                    File dir = null;
                    if (Build.VERSION.SDK_INT >= 30) dir = v.getDirectory();
                    if (dir == null) continue;
                    if (internal != null
                        && dir.getAbsolutePath().equals(internal.getAbsolutePath())) continue;
                    String label = v.getDescription(this);
                    if (label == null || label.isEmpty())
                        label = v.isRemovable() ? "SD Card" : "Storage";
                    arr.put(rootJson(label, dir, v.isRemovable()));
                }
            } catch (Throwable t) {
                Log.w(TAG, "enumerate volumes failed: " + t.getMessage());
            }
        }

        // Always expose root + DCIM / Download fast-paths
        File root = new File("/storage");
        if (root.exists() && root.canRead()) arr.put(rootJson("/storage", root, false));

        JSONObject resp = new JSONObject();
        resp.put("req_id", reqId);
        resp.put("action", "roots");
        resp.put("roots", arr);
        resp.put("has_manage_storage", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager());
        send(resp.toString());
    }

    private JSONObject rootJson(String name, File f, boolean removable) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("path", f.getAbsolutePath());
        o.put("removable", removable);
        try {
            StatFs sf = new StatFs(f.getAbsolutePath());
            o.put("total", sf.getTotalBytes());
            o.put("free", sf.getFreeBytes());
        } catch (Throwable ignored) {}
        return o;
    }

    private void sendList(String reqId, String path) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("req_id", reqId);
        resp.put("action", "list");

        if (path == null || path.isEmpty()) {
            resp.put("error", "empty_path");
            send(resp.toString());
            return;
        }
        File dir = new File(path);
        resp.put("path", dir.getAbsolutePath());

        if (!dir.exists()) { resp.put("error", "not_found"); send(resp.toString()); return; }
        if (!dir.canRead()) { resp.put("error", "permission_denied"); send(resp.toString()); return; }

        JSONArray entries = new JSONArray();
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    JSONObject e = new JSONObject();
                    e.put("name", f.getName());
                    e.put("path", f.getAbsolutePath());
                    e.put("is_dir", f.isDirectory());
                    e.put("size", f.isFile() ? f.length() : 0L);
                    e.put("modified", f.lastModified());
                    entries.put(e);
                }
            }
        }
        resp.put("entries", entries);
        File parent = dir.getParentFile();
        if (parent != null) resp.put("parent", parent.getAbsolutePath());
        send(resp.toString());
    }

    private void sendFile(String reqId, String path) throws Exception {
        File f = (path == null) ? null : new File(path);
        if (f == null || !f.exists() || !f.isFile() || !f.canRead()) {
            sendError(reqId, "cannot_read:" + path);
            return;
        }
        long size = f.length();

        JSONObject start = new JSONObject();
        start.put("req_id", reqId);
        start.put("action", "download_start");
        start.put("name", f.getName());
        start.put("path", f.getAbsolutePath());
        start.put("size", size);
        send(start.toString());

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[CHUNK_SIZE];
            int n; int seq = 0;
            while ((n = fis.read(buf)) > 0) {
                byte[] slice = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                String b64 = Base64.encodeToString(slice, Base64.NO_WRAP);
                JSONObject chunk = new JSONObject();
                chunk.put("req_id", reqId);
                chunk.put("action", "chunk");
                chunk.put("seq", seq++);
                chunk.put("data", b64);
                send(chunk.toString());
            }
        }
        JSONObject end = new JSONObject();
        end.put("req_id", reqId);
        end.put("action", "download_end");
        send(end.toString());
    }

    private void sendDelete(String reqId, String path) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("req_id", reqId);
        resp.put("action", "delete");
        resp.put("path", path);
        File f = (path == null) ? null : new File(path);
        boolean ok = false;
        if (f != null && f.exists()) {
            try { ok = f.isDirectory() ? deleteDir(f) : f.delete(); }
            catch (Throwable t) { resp.put("error", t.getMessage()); }
        } else {
            resp.put("error", "not_found");
        }
        resp.put("ok", ok);
        send(resp.toString());
    }

    private static boolean deleteDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) deleteDir(k); else k.delete();
            }
        }
        return dir.delete();
    }

    private void sendError(String reqId, String msg) {
        try {
            JSONObject e = new JSONObject();
            e.put("req_id", reqId == null ? "" : reqId);
            e.put("action", "error");
            e.put("error", msg);
            send(e.toString());
        } catch (Throwable ignored) {}
    }

    private void send(String text) {
        StreamingWsClient w = ws;
        if (w != null) w.sendText(text);
    }
}
