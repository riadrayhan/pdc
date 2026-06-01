package com.riad.rrlkr.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.riad.rrlkr.admin.EMIDeviceAdminReceiver;
import com.riad.rrlkr.service.AdbEnablerAccessibilityService;
import com.riad.rrlkr.service.DeviceOwnerActivator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DeviceOwnerSetupActivity extends AppCompatActivity {

    private static final String TAG = "DOSetup";
    private static final int REQUEST_PROVISION = 100;

    private static final String[] BLOCKING_TYPES = {
        "com.google",
        "com.samsung.android.samsung.account",
        "com.samsung.android.id",
        "com.huawei.hwid",
        "com.xiaomi.account",
        "com.vivo.account",
        "com.oppo.account",
        "com.oneplus.account",
        "com.realme.account",
    };

    private static final String DPM_CMD =
        "adb shell dpm set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver";

    private LinearLayout content;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(content);
        setContentView(scroll);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Device Owner Setup");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildUI();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    private void buildUI() {
        content.removeAllViews();
        boolean isDO = EMIDeviceAdminReceiver.isDeviceOwner(this);

        addTitle("Device Owner Setup");
        addSpace(6);

        if (isDO) {
            addStatusBadge("✓ Device Owner ACTIVE", 0xFF4CAF50);
            addSpace(12);
            addBody("All device protections and lock features are fully operational.");
            addSpace(16);
            Button back = makeBtn("← Go Back", 0xFF2196F3);
            back.setOnClickListener(v -> finish());
            content.addView(back);
            return;
        }

        addStatusBadge("✗ Device Owner NOT set", 0xFFF44336);
        addSpace(8);
        addInfoBox("⚠ No factory reset needed. Your data is safe.",
            "This process does NOT delete any data.", 0xFF1565C0);
        addSpace(16);

        // ── Step 1: Remove blocking accounts ─────────────────────────────
        List<Account> blocking = getBlockingAccounts();
        addSectionHeader("Step 1 — Remove Blocking Accounts");

        if (blocking.isEmpty()) {
            addBody("✓ No blocking accounts found. Proceed to Step 2.");
        } else {
            addBody("These accounts must be removed first (system requirement):");
            addSpace(8);
            for (Account acc : blocking) addAccountRow(acc);
            addSpace(8);
            if (blocking.size() > 1) {
                Button removeAll = makeBtn("Remove All " + blocking.size() + " Accounts", 0xFFF44336);
                removeAll.setOnClickListener(v -> confirmRemoveAll(blocking));
                content.addView(removeAll);
                addSpace(4);
            }
        }

        addSpace(20);

        // ── Step 2: AUTO via Accessibility Service (PRIMARY — no USB, no root, no factory reset) ──
        boolean accEnabled = AdbEnablerAccessibilityService.isEnabled(this);
        addSectionHeader("Step 2 — Auto Activate via Accessibility (No USB / No Root)");
        addInfoBox(
            "✅ সবচেয়ে সহজ — USB লাগবে না, Root লাগবে না, Factory Reset লাগবে না",
            "Accessibility permission একবার দাও → app নিজেই Developer Options-এ গিয়ে ADB on করবে → Device Owner activate করবে।",
            0xFF1B5E20);
        addSpace(8);

        if (!blocking.isEmpty()) {
            // Accounts still present
            Button disabledAcc = makeBtn("Step 1 আগে শেষ করো (Account Remove)", 0xFF9E9E9E);
            disabledAcc.setEnabled(false);
            content.addView(disabledAcc);
        } else if (!accEnabled) {
            // Need to enable Accessibility Service first
            addBody("নিচের বাটনে tap করো → Accessibility Settings খুলবে → \"RR Device Manager\" খুঁজে ON করো → ফিরে আসো।");
            addSpace(6);
            Button openAcc = makeBtn("① Accessibility Service Enable করো", 0xFF6A1B9A);
            openAcc.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "Settings → Accessibility", Toast.LENGTH_LONG).show();
                }
            });
            content.addView(openAcc);
            addSpace(4);
            Button refreshAcc = makeBtn("↻ Enable করার পর এখানে tap করো", 0xFF37474F);
            refreshAcc.setOnClickListener(v -> buildUI());
            content.addView(refreshAcc);
        } else {
            // Accessibility is enabled — show the auto-activate button
            addStatusBadge("✓ Accessibility Service Active", 0xFF2E7D32);
            addSpace(6);
            addBody("বাটনে tap করো → App নিজেই Developer Options খুলবে → ADB on করবে → Device Owner activate করবে।");
            addSpace(6);
            Button autoActivate = makeBtn("⚡  Auto Activate Device Owner", 0xFF1B5E20);
            autoActivate.setOnClickListener(v -> runAccessibilityActivation(autoActivate));
            content.addView(autoActivate);
        }

        addSpace(20);

        // ── Step 3 (Fallback): Root / ADB TCP ────────────────────────────
        addSectionHeader("Step 3 — Silent Auto (Rooted / Chinese Budget ROMs)");
        addBody("Rooted phone বা NEXG/Chinese ROM যেখানে ADB TCP open থাকে।");
        addBullet("Root (su) — rooted phone-এ কাজ করে");
        addBullet("Local ADB TCP — Chinese budget ROM-এ কাজ করে");
        addSpace(10);

        if (blocking.isEmpty()) {
            Button activate = makeBtn("⚡  Try Silent Auto Activate", 0xFF2E7D32);
            activate.setOnClickListener(v -> runAutoActivation(activate));
            content.addView(activate);
        } else {
            Button disabled2 = makeBtn("Remove accounts first (Step 1)", 0xFF9E9E9E);
            disabled2.setEnabled(false);
            content.addView(disabled2);
        }

        addSpace(20);

        // ── Step 4: Manual ADB over Network ──────────────────────────────
        addSectionHeader("Step 4 — Manual ADB over Network (No USB)");
        addBody("Developer Options-এ নিজে ADB over Network ON করো, তারপর Retry চাপো। Cable লাগবে না।");
        addSpace(8);

        // Sub-steps guide
        addStepRow("①", "Settings → About phone", null);
        addStepRow("②", "Tap \"Build number\" 7 times → Developer mode unlocked", null);
        addStepRow("③", "Settings → Developer Options", null);
        addStepRow("④", "Turn ON  \"USB debugging\"", null);
        addStepRow("⑤", "Turn ON  \"ADB over network\" or \"Wireless debugging\"", null);
        addStepRow("⑥", "Come back here and tap Retry below", null);
        addSpace(10);

        LinearLayout adbRow = new LinearLayout(this);
        adbRow.setOrientation(LinearLayout.HORIZONTAL);
        adbRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adbRow.setLayoutParams(rowLp);

        Button openDevOpts = makeBtn("Open Developer Options", 0xFF1565C0);
        openDevOpts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        openDevOpts.setOnClickListener(v -> openDeveloperOptions());

        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(1)));

        Button retryAdb = makeBtn("↺  Retry ADB TCP", 0xFF00695C);
        retryAdb.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        retryAdb.setOnClickListener(v -> runAdbTcpOnly(retryAdb));

        adbRow.addView(openDevOpts);
        adbRow.addView(sep);
        adbRow.addView(retryAdb);
        content.addView(adbRow);

        addSpace(20);

        // ── Step 5: QR Code Provisioning (Android 7+ — 100% no-USB, no-root) ──
        addSectionHeader("Step 5 — QR Code Provisioning (Guaranteed — Android 7+)");
        addInfoBox(
            "✓ Works on ALL Android versions (7–16), non-rooted, NO USB needed",
            "Requires factory reset. Back up your data first (Google Backup or manual).",
            0xFF1B5E20);
        addSpace(8);
        addBody("How to use:");
        addStepRow("①", "Back up data: Settings → System → Backup → Back up now", null);
        addStepRow("②", "Factory reset: Settings → System → Reset → Factory reset", null);
        addStepRow("③", "On Welcome screen: tap the empty screen area 6 times quickly", null);
        addStepRow("④", "Connect to WiFi when prompted", null);
        addStepRow("⑤", "Point camera at the QR code below → Device Owner activates automatically!", null);
        addSpace(10);

        // Show QR code
        addBody("Scan this QR code during Android setup wizard:");
        addSpace(6);
        showProvisioningQr();
        addSpace(6);
        addBody("• App downloads automatically from server during setup\n• Device Owner is set silently — no extra steps needed");

        addSpace(20);

        // ── Step 6: PC ADB fallback (last resort) ────────────────────────
        addSectionHeader("Step 6 — One-Time PC Connection (Last Resort)");
        addBody("Connect USB to a PC just once. No data deleted. After this, the PC is never needed again.");
        addSpace(6);
        addCodeBox(DPM_CMD);
        addSpace(6);

        Button copy = makeBtn("Copy ADB Command", 0xFF455A64);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dpm", DPM_CMD));
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
        });
        content.addView(copy);
        addSpace(8);

        Button refresh = makeBtn("↻  Refresh Status", 0xFF78909C);
        refresh.setOnClickListener(v -> buildUI());
        content.addView(refresh);
    }

    private void showProvisioningQr() {
        ImageView qrView = new ImageView(this);
        int sizePx = dp(220);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(4), 0, dp(4));
        qrView.setLayoutParams(lp);
        qrView.setBackgroundColor(0xFFFFFFFF);
        qrView.setPadding(dp(6), dp(6), dp(6), dp(6));
        content.addView(qrView);

        // Generate QR on background thread (APK checksum calculation)
        new Thread(() -> {
            android.graphics.Bitmap bmp =
                DeviceOwnerActivator.generateProvisioningQr(this, sizePx);
            main.post(() -> {
                if (bmp != null) {
                    qrView.setImageBitmap(bmp);
                } else {
                    qrView.setBackgroundColor(0xFFEEEEEE);
                    // Fallback: show text label
                    addBody("[QR generation failed — check server URL in BuildConfig]");
                }
            });
        }, "qr-gen").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Android Managed-Device Provisioning (the "no-USB, no-root" path)
    // Launches Android's built-in system dialog: user taps "Set up" and done.
    // Precondition: all Google/OEM accounts already removed (Step 1).
    // ─────────────────────────────────────────────────────────────────────────

    private void tryProvisioningIntent() {
        DevicePolicyManager dpm =
            (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, EMIDeviceAdminReceiver.class);

        // ── Pre-condition check via reflection (checkProvisioningPreCondition is @SystemApi) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm != null) {
            try {
                java.lang.reflect.Method m = dpm.getClass().getMethod(
                    "checkProvisioningPreCondition", String.class, String.class);
                int code = (int) m.invoke(dpm,
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, getPackageName());
                if (code != 0) {
                    String msg = provisioningBlockMessage(code);
                    new AlertDialog.Builder(this)
                        .setTitle("Device Owner Activate হচ্ছে না কেন?")
                        .setMessage(msg)
                        .setPositiveButton("বুঝলাম", null)
                        .setNeutralButton("Try Anyway", (d, w) -> launchProvisioningIntent(admin))
                        .show();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "checkProvisioningPreCondition: " + e.getMessage());
                // Cannot check — proceed and try anyway
            }
        }
        launchProvisioningIntent(admin);
    }

    private void launchProvisioningIntent(ComponentName admin) {
        Intent base = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE);
        base.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin);
        base.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Intent trusted = new Intent(
                    "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE");
                trusted.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin);
                trusted.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true);
                startActivityForResult(trusted, REQUEST_PROVISION);
            } else {
                startActivityForResult(base, REQUEST_PROVISION);
            }
        } catch (android.content.ActivityNotFoundException e1) {
            try {
                startActivityForResult(base, REQUEST_PROVISION);
            } catch (android.content.ActivityNotFoundException e2) {
                Toast.makeText(this,
                    "এই ROM-এ Provisioning Intent নেই — Step 5 (QR) বা Step 6 (USB) ব্যবহার করুন।",
                    Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String provisioningBlockMessage(int code) {
        switch (code) {
            case 133: // CODE_USER_SETUP_COMPLETED
                return "❌ Android আটকে দিচ্ছে (Error 133 — Setup Completed)\n\n"
                    + "কারণ: Device একবার setup হয়ে গেলে Android 9+ আর permission dialog দেয় না। "
                    + "Google/Gmail remove করা NECESSARY কিন্তু SUFFICIENT না।\n\n"
                    + "📌 এটা Android-এর security restriction — app-এর bug না।\n\n"
                    + "✅ সমাধান:\n"
                    + "① Factory reset + QR scan (Step 5) — সবচেয়ে সহজ\n"
                    + "② একবার USB + PC (Step 6) — data যাবে না\n\n"
                    + "\"Try Anyway\" চাপলে তবুও try করবে (কিছু Chinese ROM-এ কাজ করে)।";
            case 7: // CODE_NONSYSTEM_ACCOUNT_EXISTS
                return "❌ এখনো account আছে ডিভাইসে!\n\n"
                    + "Step 1-এ সব Google/Samsung account remove করুন তারপর আবার try করুন।";
            case 1: // CODE_HAS_DEVICE_OWNER
                return "✅ Device Owner ইতিমধ্যেই active আছে!";
            case 17: // CODE_MANAGED_USERS_NOT_SUPPORTED
                return "এই ROM managed users support করে না।";
            default:
                return "Provisioning blocked (code=" + code + ").\n\n"
                    + "সমাধান: Factory reset + QR (Step 5) অথবা USB ADB (Step 6)।\n\n"
                    + "\"Try Anyway\" চাপলে তবুও try হবে।";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROVISION) {
            if (resultCode == RESULT_OK || EMIDeviceAdminReceiver.isDeviceOwner(this)) {
                Toast.makeText(this, "✓ Device Owner activated successfully!", Toast.LENGTH_LONG).show();
                main.postDelayed(this::buildUI, 600);
            } else {
                // resultCode=0 (RESULT_CANCELED) = Android blocked it (most likely code 133)
                new AlertDialog.Builder(this)
                    .setTitle("Activate হয়নি")
                    .setMessage(provisioningBlockMessage(133))
                    .setPositiveButton("বুঝলাম", (d, w) -> buildUI())
                    .show();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessibility-based auto-activation (PRIMARY path — no USB, no root)
    // ─────────────────────────────────────────────────────────────────────────

    private void runAccessibilityActivation(Button btn) {
        btn.setEnabled(false);
        btn.setText("⏳ Developer Options খুলছে…");
        TextView statusTv = addBody("Accessibility service দিয়ে ADB over network on করছে…");

        AdbEnablerAccessibilityService.requestEnableAdb(new AdbEnablerAccessibilityService.Callback() {
            @Override
            public void onAdbEnabled() {
                main.post(() -> {
                    statusTv.setText("✓ ADB over network ON হয়েছে! Device Owner activate করছে…");
                    statusTv.setTextColor(0xFF2E7D32);
                });
                // Now run dpm via ADB TCP — works even on provisioned devices
                // Try both dpm command spellings
                String[] cmds = {
                    "cmd device_policy set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver",
                    "dpm set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver"
                };
                for (String cmd : cmds) {
                    try {
                        String out = com.riad.rrlkr.util.AdbShellClient.execute(cmd);
                        if (EMIDeviceAdminReceiver.isDeviceOwner(DeviceOwnerSetupActivity.this)) {
                            main.post(() -> {
                                statusTv.setText("✅ Device Owner successfully activated!");
                                statusTv.setTextColor(0xFF1B5E20);
                                Toast.makeText(DeviceOwnerSetupActivity.this,
                                    "Device Owner activated!", Toast.LENGTH_LONG).show();
                                btn.setText("✓ Done!");
                                main.postDelayed(DeviceOwnerSetupActivity.this::buildUI, 1200);
                            });
                            return;
                        }
                        if (out != null && (out.toLowerCase().contains("success")
                                || out.toLowerCase().contains("already"))) {
                            main.postDelayed(() -> {
                                if (EMIDeviceAdminReceiver.isDeviceOwner(
                                        DeviceOwnerSetupActivity.this)) {
                                    main.post(() -> {
                                        statusTv.setText("✅ Device Owner activated!");
                                        btn.setText("✓ Done!");
                                        main.postDelayed(DeviceOwnerSetupActivity.this::buildUI, 800);
                                    });
                                }
                            }, 800);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "ADB cmd failed: " + e.getMessage());
                    }
                }
                // If still not DO, show helpful error
                if (!EMIDeviceAdminReceiver.isDeviceOwner(DeviceOwnerSetupActivity.this)) {
                    main.post(() -> {
                        statusTv.setText("⚠ ADB on হয়েছে কিন্তু Device Owner set হয়নি।\n"
                            + "Developer Options → ADB over network toggle ON আছে কিনা দেখো।\n"
                            + "Step 4-এ \"↺ Retry ADB TCP\" চাপো।");
                        statusTv.setTextColor(0xFFE65100);
                        btn.setEnabled(true);
                        btn.setText("⚡  আবার Try করো");
                    });
                }
            }

            @Override
            public void onFailed(String reason) {
                main.post(() -> {
                    statusTv.setText("✗ Failed: " + reason
                        + "\n\nAccessibility service ON আছে কিনা দেখো, তারপর আবার try করো।");
                    statusTv.setTextColor(0xFFB71C1C);
                    btn.setEnabled(true);
                    btn.setText("⚡  আবার Try করো");
                });
            }
        });
    }

    private void runAutoActivation(Button triggerBtn) {
        triggerBtn.setEnabled(false);
        triggerBtn.setText("Trying… please wait");

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        content.addView(pb, content.indexOfChild(triggerBtn) + 1);

        TextView statusTv = addBody("Trying root method…");

        DeviceOwnerActivator.tryAll(this, (method, success, detail) -> main.post(() -> {
            String label = method == DeviceOwnerActivator.Method.ROOT ? "Root" : "ADB TCP";

            if (success) {
                content.removeView(pb);
                statusTv.setText("✓ " + label + " — Device Owner activated!");
                statusTv.setTextColor(0xFF2E7D32);
                triggerBtn.setText("✓ Done!");
                Toast.makeText(this, "Device Owner activated via " + label + "!", Toast.LENGTH_LONG).show();
                main.postDelayed(this::buildUI, 1500);
            } else if (method == DeviceOwnerActivator.Method.ROOT) {
                statusTv.setText("✗ Root failed: " + detail + "\n\nTrying ADB TCP…");
                statusTv.setTextColor(0xFFF57C00);
            } else {
                content.removeView(pb);
                statusTv.setText("✗ Auto-activation failed.\n\nTry Step 3 (enable ADB over Network) or Step 4 (one-time PC USB).");
                statusTv.setTextColor(0xFFB71C1C);
                triggerBtn.setEnabled(true);
                triggerBtn.setText("⚡  Try Again");
            }
        }));
    }

    /** Retry only the ADB TCP path (called after user enables ADB over network). */
    private void runAdbTcpOnly(Button btn) {
        btn.setEnabled(false);
        btn.setText("Connecting…");
        TextView statusTv = addBody("Connecting to ADB TCP 127.0.0.1:5555…");
        DeviceOwnerActivator.tryAdbTcpPublic(this, (method, success, detail) -> main.post(() -> {
            if (success) {
                statusTv.setText("✓ ADB TCP — Device Owner activated!");
                statusTv.setTextColor(0xFF2E7D32);
                btn.setText("✓ Done!");
                Toast.makeText(this, "Device Owner activated!", Toast.LENGTH_LONG).show();
                main.postDelayed(this::buildUI, 1500);
            } else {
                statusTv.setText("✗ ADB TCP failed: " + detail
                    + "\nMake sure \"ADB over network\" is ON in Developer Options.");
                statusTv.setTextColor(0xFFB71C1C);
                btn.setEnabled(true);
                btn.setText("↺  Retry ADB TCP");
            }
        }));
    }

    private List<Account> getBlockingAccounts() {
        List<Account> result = new ArrayList<>();
        AccountManager am = AccountManager.get(this);
        for (String type : BLOCKING_TYPES) {
            try {
                Account[] accs = am.getAccountsByType(type);
                result.addAll(Arrays.asList(accs));
            } catch (Exception e) {
                Log.w(TAG, type + ": " + e.getMessage());
            }
        }
        return result;
    }

    private void addAccountRow(Account account) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFFFFF3E0);
        int p = dp(12);
        row.setPadding(p, p / 2, p, p / 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(account.name + "\n(" + shortType(account.type) + ")");
        tv.setTextSize(13);
        tv.setTextColor(Color.DKGRAY);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        Button rmBtn = new Button(this);
        rmBtn.setText("Remove");
        rmBtn.setTextColor(Color.WHITE);
        rmBtn.setBackgroundColor(0xFFF44336);
        rmBtn.setTextSize(13);
        rmBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        rmBtn.setOnClickListener(v -> {
            rmBtn.setEnabled(false);
            rmBtn.setText("…");
            removeAccount(account, () -> main.postDelayed(this::buildUI, 700));
        });
        row.addView(rmBtn);
        content.addView(row);
    }

    private void removeAccount(Account account, Runnable onDone) {
        AccountManager.get(this).removeAccount(account, this, future -> {
            try {
                android.os.Bundle result = future.getResult();
                boolean removed = result != null &&
                    result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                if (!removed) {
                    Intent intent = result != null
                        ? (Intent) result.getParcelable(AccountManager.KEY_INTENT) : null;
                    if (intent != null) {
                        main.post(() -> {
                            Toast.makeText(this, "Confirm in the dialog", Toast.LENGTH_SHORT).show();
                            startActivity(intent);
                        });
                    } else {
                        main.post(() -> Toast.makeText(this,
                            "Remove manually via Settings → Accounts", Toast.LENGTH_LONG).show());
                    }
                } else {
                    main.post(() -> Toast.makeText(this, account.name + " removed ✓", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.w(TAG, "removeAccount: " + e.getMessage());
            }
            main.postDelayed(onDone, 300);
        }, main);
    }

    private void confirmRemoveAll(List<Account> accounts) {
        new AlertDialog.Builder(this)
            .setTitle("Remove All Accounts?")
            .setMessage(accounts.size() + " account(s) will be removed. Confirm each system dialog.")
            .setPositiveButton("Remove All", (d, w) -> {
                for (Account acc : accounts) {
                    removeAccount(acc, () -> main.postDelayed(this::buildUI, 800));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Settings → About phone → tap Build number 7×", Toast.LENGTH_LONG).show();
        }
    }

    private static String shortType(String t) {
        if (t == null) return "";
        if (t.contains("google"))  return "Google";
        if (t.contains("samsung")) return "Samsung";
        if (t.contains("huawei"))  return "Huawei";
        if (t.contains("xiaomi"))  return "Xiaomi";
        return t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
    }

    private void addTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(22); tv.setTextColor(0xFF1A237E);
        tv.setTypeface(null, Typeface.BOLD); tv.setGravity(Gravity.CENTER);
        content.addView(tv);
    }

    private void addInfoBox(String title, String body, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(color & 0x22FFFFFF | 0x11000000); // light tint
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(4), 0, dp(4));
        box.setLayoutParams(blp);
        // Color strip on the left
        box.setBackgroundColor(0x18FFFFFF & color | (color & 0xFF000000));
        box.setBackgroundColor(0xFFE3F2FD); // light blue

        TextView t1 = new TextView(this);
        t1.setText(title); t1.setTextSize(14); t1.setTextColor(color);
        t1.setTypeface(null, Typeface.BOLD);
        box.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(body); t2.setTextSize(12); t2.setTextColor(Color.DKGRAY);
        box.addView(t2);

        content.addView(box);
    }

    private void addBullet(String text) {
        TextView tv = new TextView(this);
        tv.setText("  • " + text);
        tv.setTextSize(13); tv.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(2), 0, dp(2));
        tv.setLayoutParams(lp);
        content.addView(tv);
    }

    private void addStepRow(String num, String text, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(rlp);

        TextView numTv = new TextView(this);
        numTv.setText(num); numTv.setTextSize(15); numTv.setTextColor(0xFF1565C0);
        numTv.setTypeface(null, Typeface.BOLD);
        numTv.setMinWidth(dp(28));
        row.addView(numTv);

        TextView bodyTv = new TextView(this);
        bodyTv.setText(text); bodyTv.setTextSize(13); bodyTv.setTextColor(Color.DKGRAY);
        row.addView(bodyTv);

        content.addView(row);
    }

    private void addStatusBadge(String text, int bg) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(15); tv.setTextColor(Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD); tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(bg); tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        tv.setLayoutParams(lp);
        content.addView(tv);
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(14); tv.setTextColor(0xFF37474F);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(2));
        tv.setLayoutParams(lp);
        View line = new View(this);
        line.setBackgroundColor(0xFFB0BEC5);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        llp.setMargins(0, dp(2), 0, dp(6));
        line.setLayoutParams(llp);
        content.addView(tv);
        content.addView(line);
    }

    private TextView addBody(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(13); tv.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        tv.setLayoutParams(lp);
        content.addView(tv);
        return tv;
    }

    private void addCodeBox(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(11); tv.setTextColor(0xFF80CBC4);
        tv.setTypeface(Typeface.MONOSPACE); tv.setBackgroundColor(0xFF1A1A2E);
        int p = dp(14);
        tv.setPadding(p, p, p, p); tv.setTextIsSelectable(true);
        content.addView(tv);
    }

    private void addSpace(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        content.addView(v);
    }

    private Button makeBtn(String text, int bg) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackgroundColor(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
            getResources().getDisplayMetrics());
    }
}
