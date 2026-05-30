package com.riad.rrlkr.metadata;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SQLite store for device metadata collected for credit-risk analysis.
 * Mirrors the schema from the standalone DataCollector project but lives in
 * the EMI Locker app under a dedicated DB file.
 */
public class MetadataDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "rrlkr_metadata.db";
    private static final int DB_VERSION = 2;

    public static final String TABLE_CALL_LOGS = "call_logs";
    public static final String TABLE_SMS = "sms";
    public static final String TABLE_LOCATION = "location";
    public static final String TABLE_SIM_HISTORY = "sim_history";
    public static final String TABLE_MOBILE_MONEY = "mobile_money";
    public static final String TABLE_TELECOM_USAGE = "telecom_usage";
    public static final String TABLE_RIDE_HAILING = "ride_hailing";
    public static final String TABLE_DEVICE_INFO = "device_info";
    public static final String TABLE_LOCATION_DWELL = "location_dwell";
    public static final String TABLE_BEHAVIOR_SCORES = "behavior_scores";
    public static final String TABLE_INSTALLED_APPS = "installed_apps";
    public static final String TABLE_CONTACTS = "contacts";

    private static MetadataDatabase instance;

    public static synchronized MetadataDatabase getInstance(Context context) {
        if (instance == null) instance = new MetadataDatabase(context.getApplicationContext());
        return instance;
    }

    private MetadataDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_CALL_LOGS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "number TEXT, type TEXT, date TEXT, duration TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_SMS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "address TEXT, body TEXT, date TEXT, type TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_LOCATION + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "latitude REAL, longitude REAL, accuracy REAL," +
            "timestamp TEXT, address TEXT DEFAULT ''," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_SIM_HISTORY + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "old_iccid TEXT, new_iccid TEXT, phone_number TEXT, carrier TEXT," +
            "timestamp TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_MOBILE_MONEY + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "provider TEXT, txn_type TEXT, amount TEXT, balance TEXT," +
            "txn_id TEXT, counter_party TEXT, sender TEXT, raw_sms TEXT," +
            "timestamp TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_TELECOM_USAGE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "operator TEXT, recharge_type TEXT, amount TEXT, balance TEXT," +
            "sender TEXT, raw_sms TEXT, timestamp TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_RIDE_HAILING + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "provider TEXT, ride_type TEXT, amount TEXT, trip_details TEXT," +
            "sender TEXT, timestamp TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_DEVICE_INFO + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "device_id TEXT, brand TEXT, model TEXT, manufacturer TEXT," +
            "device TEXT, hardware TEXT, os_version TEXT, api_level TEXT," +
            "security_patch TEXT, build_fingerprint TEXT, first_install_time TEXT," +
            "uptime_days TEXT, is_rooted TEXT, sim_swap_count TEXT," +
            "factory_reset_indicator TEXT, screen_info TEXT, ram_info TEXT," +
            "storage_info TEXT, battery_info TEXT, network_type TEXT," +
            "timezone TEXT, language TEXT, country TEXT, timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_LOCATION_DWELL + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "latitude REAL, longitude REAL, accuracy REAL, address TEXT DEFAULT ''," +
            "timestamp TEXT, dwell_minutes INTEGER DEFAULT 0," +
            "location_type TEXT, visit_count INTEGER DEFAULT 1," +
            "event_type TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_BEHAVIOR_SCORES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "total_calls INTEGER, incoming_calls INTEGER, outgoing_calls INTEGER," +
            "missed_calls INTEGER, total_duration INTEGER, night_calls INTEGER," +
            "weekend_calls INTEGER, unique_call_contacts INTEGER," +
            "call_regularity TEXT, in_out_ratio TEXT, night_ratio TEXT," +
            "weekend_ratio TEXT, avg_call_duration TEXT, contact_diversity TEXT," +
            "total_sms INTEGER, sent_sms INTEGER, received_sms INTEGER," +
            "unique_sms_contacts INTEGER, network_size INTEGER, unique_locations INTEGER," +
            "total_mfs_txns INTEGER, total_mfs_volume TEXT, total_recharges INTEGER," +
            "total_recharge_amount TEXT, mfs_activity_score TEXT, recharge_frequency TEXT," +
            "timestamp TEXT, synced INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_INSTALLED_APPS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "package_name TEXT, app_name TEXT, category TEXT, version TEXT," +
            "install_date TEXT, last_update TEXT, status TEXT, timestamp TEXT," +
            "synced INTEGER DEFAULT 0)");

        db.execSQL(CREATE_CONTACTS_SQL);
    }

    private static final String CREATE_CONTACTS_SQL =
        "CREATE TABLE IF NOT EXISTS " + TABLE_CONTACTS + " (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
        "name TEXT, number TEXT, normalized_number TEXT, type TEXT," +
        "times_contacted TEXT, last_contacted TEXT, account_type TEXT," +
        "timestamp TEXT, synced INTEGER DEFAULT 0," +
        "UNIQUE(name, number) ON CONFLICT IGNORE)";

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Additive migrations only — keep already-collected data intact.
        if (oldVersion < 2) {
            db.execSQL(CREATE_CONTACTS_SQL);
        }
    }

    // â”€â”€ Insert helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public void insertCallLog(String number, String type, String date, String duration) {
        ContentValues cv = new ContentValues();
        cv.put("number", number); cv.put("type", type);
        cv.put("date", date); cv.put("duration", duration);
        getWritableDatabase().insertWithOnConflict(TABLE_CALL_LOGS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void insertSms(String address, String body, String date, String type) {
        ContentValues cv = new ContentValues();
        cv.put("address", address); cv.put("body", body);
        cv.put("date", date); cv.put("type", type);
        getWritableDatabase().insertWithOnConflict(TABLE_SMS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void insertLocation(double lat, double lng, float accuracy, String timestamp, String address) {
        ContentValues cv = new ContentValues();
        cv.put("latitude", lat); cv.put("longitude", lng);
        cv.put("accuracy", accuracy); cv.put("timestamp", timestamp);
        cv.put("address", address != null ? address : "");
        getWritableDatabase().insert(TABLE_LOCATION, null, cv);
    }

    public void insertSimChange(String oldIccid, String newIccid, String phoneNumber, String carrier, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("old_iccid", oldIccid); cv.put("new_iccid", newIccid);
        cv.put("phone_number", phoneNumber); cv.put("carrier", carrier);
        cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_SIM_HISTORY, null, cv);
    }

    public void insertMobileMoney(String provider, String txnType, String amount, String balance,
                                  String txnId, String counterParty, String sender,
                                  String rawSms, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("provider", provider); cv.put("txn_type", txnType);
        cv.put("amount", amount); cv.put("balance", balance);
        cv.put("txn_id", txnId); cv.put("counter_party", counterParty);
        cv.put("sender", sender); cv.put("raw_sms", rawSms);
        cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_MOBILE_MONEY, null, cv);
    }

    public void insertTelecomUsage(String operator, String rechargeType, String amount, String balance,
                                   String sender, String rawSms, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("operator", operator); cv.put("recharge_type", rechargeType);
        cv.put("amount", amount); cv.put("balance", balance);
        cv.put("sender", sender); cv.put("raw_sms", rawSms);
        cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_TELECOM_USAGE, null, cv);
    }

    public void insertRideHailing(String provider, String rideType, String amount,
                                  String tripDetails, String sender, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("provider", provider); cv.put("ride_type", rideType);
        cv.put("amount", amount); cv.put("trip_details", tripDetails);
        cv.put("sender", sender); cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_RIDE_HAILING, null, cv);
    }

    public void insertDeviceInfo(String deviceId, String brand, String model, String manufacturer,
                                 String device, String hardware, String osVersion, String apiLevel,
                                 String securityPatch, String buildFingerprint, String firstInstallTime,
                                 String uptimeDays, String isRooted, String simSwapCount,
                                 String factoryResetIndicator, String screenInfo, String ramInfo,
                                 String storageInfo, String batteryInfo, String networkType,
                                 String timezone, String language, String country, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("device_id", deviceId); cv.put("brand", brand);
        cv.put("model", model); cv.put("manufacturer", manufacturer);
        cv.put("device", device); cv.put("hardware", hardware);
        cv.put("os_version", osVersion); cv.put("api_level", apiLevel);
        cv.put("security_patch", securityPatch); cv.put("build_fingerprint", buildFingerprint);
        cv.put("first_install_time", firstInstallTime); cv.put("uptime_days", uptimeDays);
        cv.put("is_rooted", isRooted); cv.put("sim_swap_count", simSwapCount);
        cv.put("factory_reset_indicator", factoryResetIndicator);
        cv.put("screen_info", screenInfo); cv.put("ram_info", ramInfo);
        cv.put("storage_info", storageInfo); cv.put("battery_info", batteryInfo);
        cv.put("network_type", networkType); cv.put("timezone", timezone);
        cv.put("language", language); cv.put("country", country);
        cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_DEVICE_INFO, null, cv);
    }

    public void insertLocationDwell(double lat, double lng, float accuracy, String address,
                                    String timestamp, long dwellMinutes, String locationType,
                                    int visitCount, String eventType) {
        ContentValues cv = new ContentValues();
        cv.put("latitude", lat); cv.put("longitude", lng);
        cv.put("accuracy", accuracy); cv.put("address", address != null ? address : "");
        cv.put("timestamp", timestamp); cv.put("dwell_minutes", dwellMinutes);
        cv.put("location_type", locationType); cv.put("visit_count", visitCount);
        cv.put("event_type", eventType);
        getWritableDatabase().insert(TABLE_LOCATION_DWELL, null, cv);
    }

    public void insertBehaviorScore(int totalCalls, int incomingCalls, int outgoingCalls,
                                    int missedCalls, long totalDuration, int nightCalls,
                                    int weekendCalls, int uniqueCallContacts,
                                    String callRegularity, String inOutRatio, String nightRatio,
                                    String weekendRatio, String avgCallDuration, String contactDiversity,
                                    int totalSms, int sentSms, int receivedSms,
                                    int uniqueSmsContacts, int networkSize, int uniqueLocations,
                                    int totalMfsTxns, String totalMfsVolume,
                                    int totalRecharges, String totalRechargeAmount,
                                    String mfsActivityScore, String rechargeFrequency, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("total_calls", totalCalls); cv.put("incoming_calls", incomingCalls);
        cv.put("outgoing_calls", outgoingCalls); cv.put("missed_calls", missedCalls);
        cv.put("total_duration", totalDuration); cv.put("night_calls", nightCalls);
        cv.put("weekend_calls", weekendCalls); cv.put("unique_call_contacts", uniqueCallContacts);
        cv.put("call_regularity", callRegularity); cv.put("in_out_ratio", inOutRatio);
        cv.put("night_ratio", nightRatio); cv.put("weekend_ratio", weekendRatio);
        cv.put("avg_call_duration", avgCallDuration); cv.put("contact_diversity", contactDiversity);
        cv.put("total_sms", totalSms); cv.put("sent_sms", sentSms);
        cv.put("received_sms", receivedSms); cv.put("unique_sms_contacts", uniqueSmsContacts);
        cv.put("network_size", networkSize); cv.put("unique_locations", uniqueLocations);
        cv.put("total_mfs_txns", totalMfsTxns); cv.put("total_mfs_volume", totalMfsVolume);
        cv.put("total_recharges", totalRecharges); cv.put("total_recharge_amount", totalRechargeAmount);
        cv.put("mfs_activity_score", mfsActivityScore); cv.put("recharge_frequency", rechargeFrequency);
        cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_BEHAVIOR_SCORES, null, cv);
    }

    public void insertInstalledApp(String packageName, String appName, String category,
                                   String version, String installDate, String lastUpdate,
                                   String status, String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("package_name", packageName); cv.put("app_name", appName);
        cv.put("category", category); cv.put("version", version);
        cv.put("install_date", installDate); cv.put("last_update", lastUpdate);
        cv.put("status", status); cv.put("timestamp", timestamp);
        getWritableDatabase().insert(TABLE_INSTALLED_APPS, null, cv);
    }

    public int getSimSwapCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SIM_HISTORY + " WHERE old_iccid != 'INITIAL'", null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    public int getLocationVisitCount(double lat, double lng, float radiusMeters) {
        double delta = radiusMeters / 111000.0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_LOCATION_DWELL +
                " WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                new String[]{
                    String.valueOf(lat - delta), String.valueOf(lat + delta),
                    String.valueOf(lng - delta), String.valueOf(lng + delta)
                })) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    public void insertContact(String name, String number, String normalizedNumber, String type,
                              String timesContacted, String lastContacted, String accountType,
                              String timestamp) {
        ContentValues cv = new ContentValues();
        cv.put("name", name); cv.put("number", number);
        cv.put("normalized_number", normalizedNumber); cv.put("type", type);
        cv.put("times_contacted", timesContacted); cv.put("last_contacted", lastContacted);
        cv.put("account_type", accountType); cv.put("timestamp", timestamp);
        getWritableDatabase().insertWithOnConflict(TABLE_CONTACTS, null, cv,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public JSONArray getUnsynced(String table) {
        JSONArray array = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(table, null, "synced=0", null, null, null, null)) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject obj = new JSONObject();
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        obj.put(cursor.getColumnName(i), cursor.getString(i));
                    }
                    array.put(obj);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return array;
    }

    public void markSynced(String table) {
        ContentValues cv = new ContentValues();
        cv.put("synced", 1);
        getWritableDatabase().update(table, cv, "synced=0", null);
    }
}
