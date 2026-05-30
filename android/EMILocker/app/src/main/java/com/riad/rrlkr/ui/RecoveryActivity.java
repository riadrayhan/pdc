package com.riad.rrlkr.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Lightweight, exported entry point used to recover the app when the normal
 * launcher icon ({@link MainActivity}) has been disabled (app hidden) and there
 * is no other way to bring it back. Launching this activity starts the app
 * process (running {@code App.onCreate} which re-enables the launcher), then it
 * re-enables {@link MainActivity} directly and forwards to it.
 */
public class RecoveryActivity extends Activity {

    private static final String TAG = "RecoveryActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            PackageManager pm = getPackageManager();
            ComponentName main = new ComponentName(this, MainActivity.class);
            pm.setComponentEnabledSetting(
                    main,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(open);
        } catch (Exception e) {
            Log.w(TAG, "Recovery failed: " + e.getMessage());
        }
        finish();
    }
}
