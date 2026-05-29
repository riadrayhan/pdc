package com.riad.rrlkr.ui;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.riad.rrlkr.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

/**
 * NFC Provisioning Source â€” turns this phone into a "Master Provisioning Phone".
 *
 * USE-CASE (Path C â€” Service Center Re-provisioning):
 * 1. Customer/thief did "Wipe data / factory reset" from recovery mode.
 * 2. Phone is now blank, sitting on Setup Wizard "Welcome" screen.
 * 3. Customer brings phone to your service center.
 * 4. Technician uses THIS phone (with EMI Locker installed) and opens this activity.
 * 5. Tap the two phones back-to-back (NFC bump).
 * 6. The blank phone receives the NDEF provisioning bundle and AUTOMATICALLY
 *    downloads + installs EMI Locker as Device Owner â€” re-provisioning complete
 *    in under 60 seconds. NO QR scan, NO 6-tap trick needed.
 *
 * NFC Provisioning is a STANDARD Android Enterprise flow:
 *   https://developers.google.com/android/work/play/emm-api/prov-devices#nfc_method
 *
 * Requirements:
 * - This (technician) phone must have NFC + Android Beam enabled.
 * - Target (blank) phone must have NFC + be on the very first Welcome screen.
 * - Both phones tap back-to-back; NDEF push triggers managed provisioning automatically.
 *
 * IMPORTANT: NFC beam (push) was deprecated in Android 14+ on the SOURCE side.
 * For Android 14+ technician phones, use a dedicated provisioning device running
 * Android 13 or earlier, OR use a QR code (BulkDeviceSetup page) instead.
 */
public class NfcProvisioningSourceActivity extends Activity {

    private static final String TAG = "NfcProvSource";
    private static final String MIME_PROVISIONING =
            "application/com.android.managedprovisioning";

    private static final String DEVICE_ADMIN =
            "com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver";

    // APK download location served by your backend
    private static final String APK_DOWNLOAD_LOCATION =
            BuildConfig.SERVER_URL + "/app/download";

    // SHA-256 signature checksum of your APK (Base64 URL-safe, no padding)
    // Compute via: keytool -list -v -keystore emifinance-release.jks
    // Or via the dashboard BulkDeviceSetup page.
    private static final String APK_SIGNATURE_CHECKSUM =
            "M3cJdKiSRbG7UPF_EGalAIPWoFlc-86PsVrVtj6jDA4";

    private EditText wifiSsidInput;
    private EditText wifiPasswordInput;
    private TextView statusView;
    private NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Toast.makeText(this, "This phone has no NFC â€” cannot use as provisioning source",
                    Toast.LENGTH_LONG).show();
            statusView.setText("âŒ NFC not supported on this device.\n\n" +
                    "Use a Samsung/Pixel/Xiaomi technician phone with NFC, " +
                    "or fall back to QR provisioning from the dashboard.");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable NFC in Settings first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
        }

        // Register NDEF push callback via reflection â€” Android 14+ removed the
        // beam APIs from the source side. On older Androids the call works; on
        // newer ones we fall back gracefully and instruct the user to use QR.
        boolean beamRegistered = registerLegacyBeamCallback();
        if (beamRegistered) {
            statusView.setText("âœ… Ready. Tap this phone back-to-back with the blank phone " +
                    "(target phone must be on the Welcome / Setup screen).\n\n" +
                    "Server: " + BuildConfig.SERVER_URL);
        } else {
            statusView.setText("âš ï¸ NFC beam is not available on this Android version.\n\n" +
                    "Android 14+ removed beam push from the source side. Either:\n" +
                    "  â€¢ Use an Android 13 or earlier technician phone, OR\n" +
                    "  â€¢ Open Dashboard â†’ Device Setup and use the QR code instead.\n\n" +
                    "NDEF payload (for manual NFC-tag write):\n\n" +
                    buildPayloadPreview());
        }
    }

    /** Try to call the deprecated NfcAdapter#setNdefPushMessageCallback via reflection. */
    private boolean registerLegacyBeamCallback() {
        try {
            Class<?> cbCls = Class.forName("android.nfc.NfcAdapter$CreateNdefMessageCallback");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cbCls.getClassLoader(),
                    new Class<?>[]{cbCls},
                    (p, method, args) -> {
                        if ("createNdefMessage".equals(method.getName())) {
                            NfcEvent ev = (args != null && args.length > 0) ? (NfcEvent) args[0] : null;
                            return createProvisioningMessage(ev);
                        }
                        return null;
                    });
            java.lang.reflect.Method m = NfcAdapter.class.getMethod(
                    "setNdefPushMessageCallback", cbCls, Activity.class, Activity[].class);
            m.invoke(nfcAdapter, proxy, this, new Activity[0]);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Legacy NFC beam unavailable: " + t.getMessage());
            return false;
        }
    }

    private String buildPayloadPreview() {
        try {
            Properties p = buildProvisioningProperties();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            String s = baos.toString();
            return s.length() > 600 ? s.substring(0, 600) + "â€¦" : s;
        } catch (Exception e) {
            return "(payload generation failed)";
        }
    }

    private Properties buildProvisioningProperties() {
        Properties props = new Properties();
        props.setProperty(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME",
                DEVICE_ADMIN);
        props.setProperty(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION",
                APK_DOWNLOAD_LOCATION);
        props.setProperty(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM",
                APK_SIGNATURE_CHECKSUM);
        props.setProperty(
                "android.app.extra.PROVISIONING_SKIP_ENCRYPTION", "true");
        props.setProperty(
                "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", "true");

        String ssid = wifiSsidInput.getText().toString().trim();
        String pass = wifiPasswordInput.getText().toString();
        if (!ssid.isEmpty()) {
            props.setProperty("android.app.extra.PROVISIONING_WIFI_SSID", ssid);
            if (!pass.isEmpty()) {
                props.setProperty("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "WPA");
                props.setProperty("android.app.extra.PROVISIONING_WIFI_PASSWORD", pass);
            } else {
                props.setProperty("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "NONE");
            }
        }
        return props;
    }

    /**
     * Build the NDEF message that is pushed to the target phone via NFC.
     * Android's setup wizard listens for MIME 'application/com.android.managedprovisioning'
     * and, on receipt, automatically launches managed provisioning.
     */
    private NdefMessage createProvisioningMessage(NfcEvent event) {
        try {
            Properties props = buildProvisioningProperties();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            props.store(baos, "Re-provisioning bundle from EMI Locker NFC source");

            NdefRecord record = NdefRecord.createMime(MIME_PROVISIONING, baos.toByteArray());
            Log.i(TAG, "NDEF provisioning bundle pushed (" + baos.size() + " bytes)");

            runOnUiThread(() -> Toast.makeText(this,
                    "ðŸ“¡ Provisioning bundle sent â€” target phone should start setup now",
                    Toast.LENGTH_LONG).show());

            return new NdefMessage(new NdefRecord[]{record});
        } catch (Exception e) {
            Log.e(TAG, "Failed to build NDEF message", e);
            return null;
        }
    }

    private android.view.View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("ðŸ”„ NFC Re-provisioning Source");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Use this phone to re-flash EMI Locker on a wiped device by NFC tap.\n" +
                "Target phone must be on Welcome screen with NFC enabled.");
        desc.setPadding(0, 0, 0, 24);
        root.addView(desc);

        wifiSsidInput = new EditText(this);
        wifiSsidInput.setHint("WiFi SSID (optional)");
        root.addView(wifiSsidInput);

        wifiPasswordInput = new EditText(this);
        wifiPasswordInput.setHint("WiFi password (optional)");
        root.addView(wifiPasswordInput);

        Button enableNfcBtn = new Button(this);
        enableNfcBtn.setText("Open NFC Settings");
        enableNfcBtn.setOnClickListener(v ->
                startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS)));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, 24, 0, 0);
        root.addView(enableNfcBtn, btnLp);

        statusView = new TextView(this);
        statusView.setPadding(0, 32, 0, 0);
        statusView.setMovementMethod(new ScrollingMovementMethod());
        root.addView(statusView);

        return root;
    }
}
