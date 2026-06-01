package com.riad.rrlkr.service;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.riad.rrlkr.BuildConfig;
import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.util.AdbShellClient;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;

/**
 * Tries every available method to set this app as Device Owner, in order:
 *
 *  1. Root (su) — works on rooted devices / factory unlocked ROMs.
 *  2. ADB-over-TCP to 127.0.0.1:5555 — works on userdebug / custom ROMs
 *     that keep ADB TCP open without authentication (many Chinese budget phones).
 *
 * Also generates a provisioning QR code that can be scanned during the
 * Android setup wizard after a factory reset — the proper enterprise path
 * that requires zero PC interaction.
 */
public final class DeviceOwnerActivator {

    private static final String TAG = "DOActivator";

    private static final String COMPONENT =
        "com.riad.rrlkr/.admin.EMIDeviceAdminReceiver";

    // Both command spellings; "cmd device_policy" is Android 12+, "dpm" works on 7-11
    private static final String[] DPM_CMDS = {
        "cmd device_policy set-device-owner " + COMPONENT,
        "dpm set-device-owner " + COMPONENT,
    };

    // APK distribution endpoint served by the Node backend
    private static final String APK_DOWNLOAD_URL =
        BuildConfig.SERVER_URL.replace("/api/v1", "")
        + "/api/v1/app-distribution/latest";

    public enum Method { ROOT, ADB_TCP }

    public interface ActivationCallback {
        /** Called on background thread. */
        void onResult(Method method, boolean success, String detail);
    }

    private DeviceOwnerActivator() {}

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    public static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager dpm =
            (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
    }

    /**
     * Public ADB-TCP-only attempt. Called from DeviceOwnerSetupActivity when the user
     * explicitly enables "ADB over network" and taps Retry.
     */
    public static void tryAdbTcpPublic(Context ctx, ActivationCallback cb) {
        new Thread(() -> tryAdbTcp(ctx, cb), "do-adb-tcp").start();
    }

    /**
     * Attempt activation sequentially (root → ADB TCP).
     * Fires callback after each attempt so UI can update status.
     */
    public static void tryAll(Context ctx, ActivationCallback cb) {
        new Thread(() -> {
            // ── Method 1: root ────────────────────────────────────────────
            String rootResult = tryRoot();
            if (rootResult != null && isDeviceOwner(ctx)) {
                cb.onResult(Method.ROOT, true, rootResult);
                return;
            }
            cb.onResult(Method.ROOT, false,
                rootResult != null ? rootResult : "su not available");

            if (isDeviceOwner(ctx)) return; // paranoia

            // ── Method 2: ADB TCP ─────────────────────────────────────────
            tryAdbTcp(ctx, cb);

        }, "do-activator").start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Method 1 — Root
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @return output string if su was found; null if su not present.
     */
    private static String tryRoot() {
        // First attempt: directly set-device-owner via su
        for (String cmd : DPM_CMDS) {
            try {
                Log.i(TAG, "Root attempt: su -c \"" + cmd + "\"");
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                int exit = p.waitFor();
                String out = sb.toString().trim();
                Log.i(TAG, "Root result [" + exit + "]: " + out);
                if (exit == 0 || out.toLowerCase().contains("success")) {
                    return "Root: " + (out.isEmpty() ? "OK" : out);
                }
                if (!out.isEmpty()) return "Root: " + out;
            } catch (Exception e) {
                Log.w(TAG, "Root attempt failed: " + e.getMessage());
            }
        }

        // Second attempt: use root to enable ADB TCP, then ADB shell will connect locally
        try {
            Log.i(TAG, "Root: enabling ADB TCP via su");
            String enableAdb =
                "settings put global adb_enabled 1 ; " +
                "setprop service.adb.tcp.port 5555 ; " +
                "stop adbd ; start adbd";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", enableAdb});
            p.waitFor();
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            // Now try ADB shell via the newly opened port
            for (String cmd : DPM_CMDS) {
                try {
                    String out = AdbShellClient.execute(cmd);
                    if (out.toLowerCase().contains("success") || out.isEmpty()) {
                        return "Root+ADB TCP: " + (out.isEmpty() ? "OK" : out);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Root+ADB TCP failed: " + e.getMessage());
        }

        return null; // su not available
    }

    // ─────────────────────────────────────────────────────────────────────
    // Method 2 — ADB over TCP (localhost:5555)
    // ─────────────────────────────────────────────────────────────────────

    private static void tryAdbTcp(Context ctx, ActivationCallback cb) {
        // Try both DPM command spellings
        for (String cmd : DPM_CMDS) {
            AdbShellClient.runCommand(cmd, new AdbShellClient.Callback() {
                @Override
                public void onSuccess(String output) {
                    Log.i(TAG, "ADB TCP result: " + output);
                    boolean ok = isDeviceOwner(ctx);
                    cb.onResult(Method.ADB_TCP, ok,
                        ok ? "ADB TCP: success" : "ADB TCP replied but DO not set: " + output);
                }

                @Override
                public void onFailure(String reason) {
                    Log.w(TAG, "ADB TCP cmd failed: " + reason);
                    cb.onResult(Method.ADB_TCP, false, "ADB TCP: " + reason);
                }
            });
            // Give it a moment
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            if (isDeviceOwner(ctx)) return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Provisioning QR code (for factory-reset Android 7+ setup wizard)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds the Android Enterprise provisioning QR bitmap.
     * Scan this during the device setup wizard (tap the welcome screen 6×
     * or connect to wifi → back → tap 6×) to auto-install the app as DO.
     */
    public static Bitmap generateProvisioningQr(Context ctx, int sizePixels) {
        String checksum = computeApkChecksum(ctx);
        String json = buildProvisioningJson(checksum);
        Log.i(TAG, "Provisioning JSON: " + json);
        return encodeQr(json, sizePixels);
    }

    private static String buildProvisioningJson(String checksum) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\":")
          .append("\"").append(COMPONENT).append("\",");
        sb.append("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION\":")
          .append("\"").append(APK_DOWNLOAD_URL).append("\",");
        if (checksum != null && !checksum.isEmpty()) {
            sb.append("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM\":")
              .append("\"").append(checksum).append("\",");
        }
        sb.append("\"android.app.extra.PROVISIONING_SKIP_ENCRYPTION\":false,");
        sb.append("\"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED\":true");
        sb.append("}");
        return sb.toString();
    }

    /**
     * SHA-256 of the installed APK, base64url-encoded without padding.
     * Android provisioning uses this to verify the downloaded APK.
     */
    private static String computeApkChecksum(Context ctx) {
        try {
            String path = ctx.getPackageCodePath();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (FileInputStream fis = new FileInputStream(new File(path))) {
                int n;
                while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
            }
            return Base64.encodeToString(md.digest(),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "APK checksum failed: " + e.getMessage());
            return "";
        }
    }

    private static Bitmap encodeQr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                .encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bmp;
        } catch (WriterException e) {
            Log.e(TAG, "QR encode failed", e);
            return null;
        }
    }
}
