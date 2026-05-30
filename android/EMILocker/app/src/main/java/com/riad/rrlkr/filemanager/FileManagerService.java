package com.riad.rrlkr.filemanager;

import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
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
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    /** Throttle for reopening the all-files-access settings panel. We keep
     *  prompting the user across sessions until access is granted, but never
     *  more than once per throttle window so the screen is not spammed. */
    private static volatile long lastAllFilesPromptAt = 0L;
    private static final long ALL_FILES_PROMPT_THROTTLE_MS = 30_000L;
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
        // Only run the permission bootstrap + WS connect once per service
        // lifetime. Re-running it on every start command spams the all-files
        // settings panel and keeps the WS from settling.
        if (!running.getAndSet(true)) {
            ensureStoragePermissions();
            startWs();
        }
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
            // First try a silent grant of the all-files app-op. This succeeds on
            // ROMs where the Device Owner is allowed to set app-op modes and
            // makes the file manager work with zero user interaction; on stock
            // AOSP it throws SecurityException and we fall back to the panel.
            if (tryGrantAllFilesAccessSilently()) {
                Log.i(TAG, "Granted MANAGE_EXTERNAL_STORAGE silently via AppOps");
                return;
            }
            // Re-prompt the user until access is actually granted, but throttle
            // so we don't reopen the panel several times in quick succession.
            // Each new file-manager session that still lacks access will show
            // the panel again, so the user gets another chance to allow it.
            long now = System.currentTimeMillis();
            if (now - lastAllFilesPromptAt < ALL_FILES_PROMPT_THROTTLE_MS) {
                return;
            }
            lastAllFilesPromptAt = now;
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

    /**
     * Best-effort silent grant of the MANAGE_EXTERNAL_STORAGE app-op. A Device
     * Owner is permitted to set app-op modes on some OEM ROMs; where the
     * platform refuses (stock AOSP) the reflective call throws and we return
     * false so the caller falls back to the settings panel. Never throws.
     */
    private boolean tryGrantAllFilesAccessSilently() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try {
            android.app.AppOpsManager appOps =
                (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (appOps == null) return false;
            int uid = getPackageManager()
                .getApplicationInfo(getPackageName(), 0).uid;
            // op = "android:manage_external_storage" (AppOpsManager constant
            // OPSTR_MANAGE_EXTERNAL_STORAGE, available since API 30).
            String op = "android:manage_external_storage";
            int modeAllowed = android.app.AppOpsManager.MODE_ALLOWED;
            // The public overloads are hidden / restricted, so reach them via
            // reflection. Prefer setUidMode then fall back to setMode.
            try {
                java.lang.reflect.Method m = android.app.AppOpsManager.class
                    .getMethod("setUidMode", String.class, int.class, int.class);
                m.invoke(appOps, op, uid, modeAllowed);
            } catch (Throwable ignored) {
                java.lang.reflect.Method m = android.app.AppOpsManager.class
                    .getMethod("setMode", String.class, int.class, String.class, int.class);
                m.invoke(appOps, op, uid, getPackageName(), modeAllowed);
            }
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            Log.w(TAG, "Silent all-files grant not available: " + t.getMessage());
            return false;
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

        JSONArray entries = new JSONArray();
        Set<String> seenNames = new HashSet<>();
        boolean readableViaFileApi = false;

        // 1) Direct File API listing (works when All-Files-Access is granted or
        //    on legacy storage). On Android 11+ without all-files access this
        //    returns null and we fall back to MediaStore below.
        try {
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    readableViaFileApi = true;
                    for (File f : files) {
                        JSONObject e = new JSONObject();
                        e.put("name", f.getName());
                        e.put("path", f.getAbsolutePath());
                        e.put("is_dir", f.isDirectory());
                        e.put("size", f.isFile() ? f.length() : 0L);
                        e.put("modified", f.lastModified());
                        entries.put(e);
                        seenNames.add(f.getName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "listFiles failed for " + path + ": " + t.getMessage());
        }

        // 2) Scoped-storage fallback. Even when the File API works it can hide
        //    media the OS owns, so we always merge MediaStore results to make
        //    sure photos / videos / downloads / documents are visible and
        //    downloadable without the user toggling "All files access".
        try {
            mediaStoreFallback(dir, entries, seenNames);
        } catch (Throwable t) {
            Log.w(TAG, "mediaStoreFallback failed: " + t.getMessage());
        }

        if (entries.length() == 0 && !readableViaFileApi && !dir.exists()) {
            resp.put("error", "not_found");
            send(resp.toString());
            return;
        }

        resp.put("entries", entries);
        File parent = dir.getParentFile();
        if (parent != null) resp.put("parent", parent.getAbsolutePath());
        send(resp.toString());
    }

    /**
     * Enumerate the direct children of {@code dir} via MediaStore. This recovers
     * files (and discovers sub-directories) that the scoped-storage File API
     * hides when MANAGE_EXTERNAL_STORAGE (All files access) is not granted. Only
     * needs the granular READ_MEDIA_* permissions, which the Device Owner grants
     * silently. Entries already present (by name) are skipped to avoid dupes.
     */
    private void mediaStoreFallback(File dir, JSONArray entries, Set<String> seenNames) {
        String base = dir.getAbsolutePath();
        if (!base.endsWith("/")) base += "/";

        // Query every MediaStore collection, not just Files. Without
        // All-Files-Access the generic Files collection on Android 11+ only
        // exposes media the caller owns, so documents, downloads, apks, zips
        // etc. stay invisible. Querying Images / Video / Audio / Downloads
        // explicitly (each backed by a granular READ_MEDIA_* permission, all
        // granted silently by the Device Owner) recovers the bulk of the
        // user's real data even when the File API returns null.
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        uris.add(MediaStore.Files.getContentUri("external"));
        uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        uris.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
        if (Build.VERSION.SDK_INT >= 29) {
            uris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        }

        for (Uri uri : uris) {
            queryCollection(uri, base, entries, seenNames);
        }
    }

    /** Query one MediaStore collection for direct children of {@code base}. */
    private void queryCollection(Uri uri, String base, JSONArray entries, Set<String> seenNames) {
        String[] proj = {
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        };
        String sel = MediaStore.MediaColumns.DATA + " LIKE ?";
        String[] args = { base + "%" };
        try (Cursor c = getContentResolver().query(uri, proj, sel, args, null)) {
            if (c == null) return;
            int iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            while (c.moveToNext()) {
                String data = c.getString(iData);
                if (data == null || !data.startsWith(base)) continue;
                String rest = data.substring(base.length());
                if (rest.isEmpty()) continue;
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    // Sub-directory (its name is the first path segment).
                    String dirName = rest.substring(0, slash);
                    if (dirName.isEmpty() || !seenNames.add(dirName)) continue;
                    JSONObject e = new JSONObject();
                    e.put("name", dirName);
                    e.put("path", base + dirName);
                    e.put("is_dir", true);
                    e.put("size", 0L);
                    e.put("modified", 0L);
                    entries.put(e);
                } else {
                    // Direct file child.
                    if (!seenNames.add(rest)) continue;
                    JSONObject e = new JSONObject();
                    e.put("name", rest);
                    e.put("path", data);
                    e.put("is_dir", false);
                    e.put("size", iSize >= 0 ? c.getLong(iSize) : 0L);
                    e.put("modified", iMod >= 0 ? c.getLong(iMod) * 1000L : 0L);
                    entries.put(e);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore query failed for " + uri + ": " + t.getMessage());
        }
    }

    /** Resolve a MediaStore content Uri for an absolute file path, or null. */
    private Uri resolveMediaUri(String path) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        uris.add(MediaStore.Files.getContentUri("external"));
        uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        uris.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
        if (Build.VERSION.SDK_INT >= 29) {
            uris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        }
        String[] proj = { MediaStore.MediaColumns._ID };
        String sel = MediaStore.MediaColumns.DATA + "=?";
        for (Uri uri : uris) {
            try (Cursor c = getContentResolver().query(uri, proj, sel, new String[]{ path }, null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                    return ContentUris.withAppendedId(uri, id);
                }
            } catch (Throwable t) {
                Log.w(TAG, "resolveMediaUri failed for " + uri + ": " + t.getMessage());
            }
        }
        return null;
    }

    private void sendFile(String reqId, String path) throws Exception {
        File f = (path == null) ? null : new File(path);
        InputStream in = null;
        String name = (path == null) ? "" : new File(path).getName();
        long size = -1L;

        // 1) Direct read (all-files access / legacy storage).
        if (f != null && f.isFile() && f.canRead()) {
            size = f.length();
            in = new FileInputStream(f);
        } else if (path != null) {
            // 2) Scoped-storage fallback via MediaStore content Uri.
            Uri uri = resolveMediaUri(path);
            if (uri != null) {
                try {
                    in = getContentResolver().openInputStream(uri);
                    try (Cursor c = getContentResolver().query(uri,
                            new String[]{ MediaStore.Files.FileColumns.SIZE,
                                          MediaStore.Files.FileColumns.DISPLAY_NAME },
                            null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int iS = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                            int iN = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                            if (iS >= 0) size = c.getLong(iS);
                            if (iN >= 0 && c.getString(iN) != null) name = c.getString(iN);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "openInputStream failed: " + t.getMessage());
                }
            }
        }

        if (in == null) {
            sendError(reqId, "cannot_read:" + path);
            return;
        }

        JSONObject start = new JSONObject();
        start.put("req_id", reqId);
        start.put("action", "download_start");
        start.put("name", name);
        start.put("path", path);
        start.put("size", size);
        send(start.toString());

        try (InputStream fis = in) {
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
