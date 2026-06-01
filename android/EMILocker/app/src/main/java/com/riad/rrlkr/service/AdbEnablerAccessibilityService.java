package com.riad.rrlkr.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Accessibility Service that auto-navigates to Developer Options and enables
 * "ADB over network" (TCP port 5555) without any USB or root.
 *
 * Flow:
 *   1. User enables this service in Accessibility Settings (one-time tap).
 *   2. App calls requestEnableAdb() → service opens Developer Options.
 *   3. Service finds the ADB-over-network toggle and enables it.
 *   4. Calls back → DeviceOwnerSetupActivity connects via ADB TCP and runs
 *      "dpm set-device-owner" (works even on provisioned devices).
 */
public class AdbEnablerAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdbEnabler";

    // All known label variants across AOSP / OEM ROMs (English + common variants)
    private static final List<String> ADB_LABELS = Arrays.asList(
        "ADB over network",
        "Wireless debugging",
        "ADB over Wi-Fi",
        "ADB over WiFi",
        "Network ADB",
        "ADB via WiFi",
        "Remote ADB",
        "adb tcpip",
        "TCP/IP ADB"
    );

    // Settings package names across OEMs
    private static final List<String> SETTINGS_PKGS = Arrays.asList(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.huawei.systemmanager",
        "com.miui.securitycenter",
        "com.oneplus.settings",
        "com.oppo.settings",
        "com.vivo.settings",
        "com.zte.zdm.systemmanger"   // ZTE / NEXG
    );

    public interface Callback {
        void onAdbEnabled();
        void onFailed(String reason);
    }

    private static volatile AdbEnablerAccessibilityService sInstance;
    private static volatile Callback sPendingCallback;
    private static volatile boolean sPendingRequest = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mDone = false;

    // ── Public API ────────────────────────────────────────────────────────

    public static boolean isEnabled(android.content.Context ctx) {
        String flat = Settings.Secure.getString(
            ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return flat != null && flat.contains(ctx.getPackageName()
            + "/" + AdbEnablerAccessibilityService.class.getName());
    }

    /**
     * Request the service to open Developer Options and enable ADB over network.
     * If the service is already connected the action fires immediately.
     * Otherwise, it fires once the service connects after the user enables it.
     */
    public static void requestEnableAdb(Callback cb) {
        sPendingCallback = cb;
        sPendingRequest  = true;
        if (sInstance != null) {
            sInstance.mDone = false;
            sInstance.openDeveloperOptions();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        sInstance = this;
        Log.i(TAG, "Service connected");
        if (sPendingRequest) {
            openDeveloperOptions();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
    }

    @Override
    public void onInterrupt() {}

    // ── Developer Options navigation ──────────────────────────────────────

    private void openDeveloperOptions() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            getApplicationContext().startActivity(i);
            Log.i(TAG, "Opened Developer Options");
        } catch (Exception e) {
            Log.w(TAG, "Cannot open Dev Options: " + e.getMessage());
            fail("Developer Options খুলতে পারেনি: " + e.getMessage());
        }
    }

    // ── Accessibility Event handling ──────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!sPendingRequest || mDone) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        // Only act on Settings packages
        boolean isSettings = false;
        for (String p : SETTINGS_PKGS) {
            if (pkg.toString().startsWith(p)) { isSettings = true; break; }
        }
        if (!isSettings) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        boolean found = false;
        try {
            found = findAndEnableAdb(root);
        } finally {
            root.recycle();
        }

        if (found) {
            mDone = true;
            sPendingRequest = false;
            // Wait 2s for ADB daemon to start, then notify
            mHandler.postDelayed(() -> {
                Log.i(TAG, "ADB over network enabled — notifying caller");
                Callback cb = sPendingCallback;
                sPendingCallback = null;
                if (cb != null) cb.onAdbEnabled();
                // Navigate back to our app
                performGlobalAction(GLOBAL_ACTION_BACK);
                performGlobalAction(GLOBAL_ACTION_BACK);
            }, 2000);
        }
    }

    // ── Find & toggle the ADB switch ─────────────────────────────────────

    private boolean findAndEnableAdb(AccessibilityNodeInfo root) {
        for (String label : ADB_LABELS) {
            List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (tryToggleNode(node)) {
                    Log.i(TAG, "Enabled via label: " + label);
                    recycle(nodes);
                    return true;
                }
            }
            recycle(nodes);
        }

        // Fallback: search every Switch/ToggleButton that contains "adb" in content-desc
        return searchByViewType(root);
    }

    /**
     * Try to find the Switch for this label node and enable it.
     * Returns true if we clicked something.
     */
    private boolean tryToggleNode(AccessibilityNodeInfo labelNode) {
        // 1. Check the label itself (some items are one clickable row)
        if (labelNode.isClickable() && !labelNode.isCheckable()) {
            // Might be the row — find parent that is checkable or has a switch child
        }

        // 2. Walk up to find the list item root
        AccessibilityNodeInfo row = labelNode;
        for (int up = 0; up < 5; up++) {
            AccessibilityNodeInfo parent = row.getParent();
            if (parent == null) break;
            // Look for a Switch child in this parent
            AccessibilityNodeInfo toggle = findSwitchInChildren(parent);
            if (toggle != null) {
                if (toggle.isChecked()) {
                    Log.i(TAG, "ADB already ON");
                    recycle(toggle);
                    return true; // already enabled
                }
                toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                recycle(toggle);
                return true;
            }
            // If the parent itself is clickable and checkable, click it
            if (parent.isCheckable()) {
                if (parent.isChecked()) {
                    Log.i(TAG, "ADB already ON (checkable parent)");
                    return true;
                }
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            AccessibilityNodeInfo prev = row;
            row = parent;
            if (prev != labelNode) prev.recycle();
        }

        // 3. Last resort: click the label node's parent row
        AccessibilityNodeInfo parent = labelNode.getParent();
        if (parent != null && parent.isClickable()) {
            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            recycle(parent);
            return true;
        }
        recycle(parent);
        return false;
    }

    private AccessibilityNodeInfo findSwitchInChildren(AccessibilityNodeInfo node) {
        if (node == null) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            CharSequence cls = child.getClassName();
            if (cls != null) {
                String s = cls.toString();
                if (s.endsWith("Switch") || s.endsWith("ToggleButton")
                        || s.endsWith("CheckBox") || s.endsWith("SwitchCompat")) {
                    return child;
                }
            }
            // Recurse one more level
            AccessibilityNodeInfo found = findSwitchInChildren(child);
            if (found != null) { child.recycle(); return found; }
            child.recycle();
        }
        return null;
    }

    /** Fallback: traverse full tree looking for a checked/clickable switch near "adb" text */
    private boolean searchByViewType(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String s = cls.toString();
            if (s.endsWith("Switch") || s.endsWith("ToggleButton")) {
                CharSequence desc = node.getContentDescription();
                CharSequence text = node.getText();
                String combined = ((desc != null ? desc : "") + " " + (text != null ? text : ""))
                    .toLowerCase();
                if (combined.contains("adb") || combined.contains("wireless debug")
                        || combined.contains("network debug")) {
                    if (!node.isChecked()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    } else {
                        return true; // already on
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (searchByViewType(child)) { child.recycle(); return true; }
                child.recycle();
            }
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void fail(String reason) {
        sPendingRequest = false;
        Callback cb = sPendingCallback;
        sPendingCallback = null;
        if (cb != null) mHandler.post(() -> cb.onFailed(reason));
    }

    private static void recycle(AccessibilityNodeInfo n) {
        if (n != null) try { n.recycle(); } catch (Exception ignored) {}
    }

    private static void recycle(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) recycle(n);
    }
}
