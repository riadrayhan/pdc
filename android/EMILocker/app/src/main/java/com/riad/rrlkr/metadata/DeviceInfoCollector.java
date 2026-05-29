package com.riad.rrlkr.metadata;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class DeviceInfoCollector {

    private final Context context;
    private final MetadataDatabase db;
    private static final String PREFS_NAME = "rrlkr_md_device_prefs";
    private static final String KEY_FIRST_INSTALL = "first_install_time";
    private static final String KEY_BOOT_COUNT = "boot_count";

    public DeviceInfoCollector(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void collect() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        String osVersion = Build.VERSION.RELEASE;
        int apiLevel = Build.VERSION.SDK_INT;
        String securityPatch = Build.VERSION.SDK_INT >= 23 ? Build.VERSION.SECURITY_PATCH : "";

        long firstInstallTime = getFirstInstallTime();
        long deviceUptime = SystemClock.elapsedRealtime();
        String uptimeDays = String.valueOf(deviceUptime / (1000L * 60 * 60 * 24));

        boolean isRooted = checkRoot();
        int simSwapCount = db.getSimSwapCount();
        int factoryResetIndicator = detectFactoryReset();

        db.insertDeviceInfo(deviceId, Build.BRAND, Build.MODEL, Build.MANUFACTURER, Build.DEVICE,
            Build.HARDWARE, osVersion, String.valueOf(apiLevel), securityPatch, Build.FINGERPRINT,
            String.valueOf(firstInstallTime), uptimeDays, isRooted ? "YES" : "NO",
            String.valueOf(simSwapCount), String.valueOf(factoryResetIndicator),
            getScreenInfo(), getRamInfo(), getStorageInfo(), getBatteryInfo(), getNetworkType(),
            TimeZone.getDefault().getID(), Locale.getDefault().getLanguage(),
            Locale.getDefault().getCountry(), timestamp);
    }

    private long getFirstInstallTime() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long savedTime = prefs.getLong(KEY_FIRST_INSTALL, 0);
        if (savedTime == 0) {
            try {
                savedTime = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).firstInstallTime;
            } catch (Exception e) {
                savedTime = System.currentTimeMillis();
            }
            prefs.edit().putLong(KEY_FIRST_INSTALL, savedTime).apply();
        }
        return savedTime;
    }

    private boolean checkRoot() {
        String[] rootPaths = {
            "/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su",
            "/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/system/app/SuperSU.apk", "/system/app/SuperSU", "/system/app/Magisk.apk"
        };
        for (String p : rootPaths) if (new File(p).exists()) return true;
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) return true;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (line != null && !line.isEmpty()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private int detectFactoryReset() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int bootCount = prefs.getInt(KEY_BOOT_COUNT, 0);
        if (bootCount == 0) {
            try {
                long firstInstall = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).firstInstallTime;
                long now = System.currentTimeMillis();
                bootCount = (now - firstInstall) < 3600000 ? 0 : -1;
            } catch (Exception e) {
                bootCount = 0;
            }
        }
        prefs.edit().putInt(KEY_BOOT_COUNT, bootCount + 1).apply();
        return bootCount;
    }

    private String getScreenInfo() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getMetrics(dm);
                return dm.widthPixels + "x" + dm.heightPixels + " " + dm.densityDpi + "dpi";
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private String getRamInfo() {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (mi.totalMem / (1024 * 1024)) + "MB total, " + (mi.availMem / (1024 * 1024)) + "MB available";
        } catch (Exception e) { return "unknown"; }
    }

    private String getStorageInfo() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalGB = (stat.getBlockSizeLong() * stat.getBlockCountLong()) / (1024 * 1024 * 1024);
            long freeGB = (stat.getBlockSizeLong() * stat.getAvailableBlocksLong()) / (1024 * 1024 * 1024);
            return totalGB + "GB total, " + freeGB + "GB free";
        } catch (Exception e) { return "unknown"; }
    }

    private String getBatteryInfo() {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) return "unknown";
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
            String charging;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING: charging = "charging"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING: charging = "discharging"; break;
                case BatteryManager.BATTERY_STATUS_FULL: charging = "full"; break;
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: charging = "not_charging"; break;
                default: charging = "unknown";
            }
            return level + "%, " + charging;
        } catch (Exception e) { return "unknown"; }
    }

    private String getNetworkType() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "unknown";
            int type = tm.getDataNetworkType();
            switch (type) {
                case TelephonyManager.NETWORK_TYPE_LTE: return "4G/LTE";
                case TelephonyManager.NETWORK_TYPE_NR: return "5G";
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
                default: return "type_" + type;
            }
        } catch (SecurityException e) { return "no_permission"; }
        catch (Exception e) { return "unknown"; }
    }
}
