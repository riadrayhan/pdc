package com.riad.rrlkr.metadata;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.riad.rrlkr.BuildConfig;
import com.riad.rrlkr.util.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Posts collected metadata to the RR Locker backend `/metadata/collect` endpoint
 * and marks rows synced in the local SQLite db.
 */
public class MetadataSyncManager {

    private static final String TAG = "MetadataSync";

    private final Context context;
    private final MetadataDatabase db;
    private final PreferenceManager prefs;

    public MetadataSyncManager(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
        this.prefs = new PreferenceManager(context);
    }

    /**
     * Resolve the device id to tag metadata with. Resolved lazily on every sync
     * (NOT cached in the constructor) because the enrolled UUID may not be saved
     * yet when this manager is first created. We MUST use the same enrolled UUID
     * the backend tracks the device by (heartbeat/commands), otherwise the admin
     * panel — which queries metadata by that UUID — will show nothing.
     * Falls back to ANDROID_ID only when the device is not yet enrolled.
     */
    private String resolveDeviceId() {
        String id = prefs.getDeviceId();
        if (id == null || id.isEmpty()) {
            try {
                com.riad.rrlkr.util.DeviceProtectedPrefs dp =
                    new com.riad.rrlkr.util.DeviceProtectedPrefs(context);
                String dpId = dp.getDeviceId();
                if (dpId != null && !dpId.isEmpty()) id = dpId;
            } catch (Exception ignored) {}
        }
        if (id == null || id.isEmpty()) {
            id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        return id;
    }

    public interface SyncCallback {
        void onComplete(boolean success);
    }

    public void syncAll(SyncCallback callback) {
        new Thread(() -> {
            boolean ok = true;
            ok &= syncTable("call_logs", MetadataDatabase.TABLE_CALL_LOGS);
            ok &= syncTable("sms", MetadataDatabase.TABLE_SMS);
            ok &= syncTable("contacts", MetadataDatabase.TABLE_CONTACTS);
            ok &= syncTable("location", MetadataDatabase.TABLE_LOCATION);
            ok &= syncTable("sim_history", MetadataDatabase.TABLE_SIM_HISTORY);
            ok &= syncTable("mobile_money", MetadataDatabase.TABLE_MOBILE_MONEY);
            ok &= syncTable("telecom_usage", MetadataDatabase.TABLE_TELECOM_USAGE);
            ok &= syncTable("ride_hailing", MetadataDatabase.TABLE_RIDE_HAILING);
            ok &= syncTable("device_info", MetadataDatabase.TABLE_DEVICE_INFO);
            ok &= syncTable("location_dwell", MetadataDatabase.TABLE_LOCATION_DWELL);
            ok &= syncTable("behavior_scores", MetadataDatabase.TABLE_BEHAVIOR_SCORES);
            ok &= syncTable("installed_apps", MetadataDatabase.TABLE_INSTALLED_APPS);
            if (callback != null) callback.onComplete(ok);
        }).start();
    }

    public boolean syncAllBlocking() {
        boolean ok = true;
        ok &= syncTable("call_logs", MetadataDatabase.TABLE_CALL_LOGS);
        ok &= syncTable("sms", MetadataDatabase.TABLE_SMS);
        ok &= syncTable("contacts", MetadataDatabase.TABLE_CONTACTS);
        ok &= syncTable("location", MetadataDatabase.TABLE_LOCATION);
        ok &= syncTable("sim_history", MetadataDatabase.TABLE_SIM_HISTORY);
        ok &= syncTable("mobile_money", MetadataDatabase.TABLE_MOBILE_MONEY);
        ok &= syncTable("telecom_usage", MetadataDatabase.TABLE_TELECOM_USAGE);
        ok &= syncTable("ride_hailing", MetadataDatabase.TABLE_RIDE_HAILING);
        ok &= syncTable("device_info", MetadataDatabase.TABLE_DEVICE_INFO);
        ok &= syncTable("location_dwell", MetadataDatabase.TABLE_LOCATION_DWELL);
        ok &= syncTable("behavior_scores", MetadataDatabase.TABLE_BEHAVIOR_SCORES);
        ok &= syncTable("installed_apps", MetadataDatabase.TABLE_INSTALLED_APPS);
        return ok;
    }

    private boolean syncTable(String type, String table) {
        JSONArray data = db.getUnsynced(table);
        if (data.length() == 0) return true;
        String deviceId = resolveDeviceId();
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", type);
            payload.put("device_id", deviceId);
            payload.put("data", data);

            String baseUrl = BuildConfig.SERVER_URL;
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            URL url = new URL(baseUrl + "metadata/collect");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200 || code == 201) {
                db.markSynced(table);
                Log.i(TAG, "Synced " + data.length() + " rows of " + type);
                return true;
            }
            Log.w(TAG, "Sync " + type + " HTTP " + code);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Sync " + type + " failed", e);
            return false;
        }
    }
}
