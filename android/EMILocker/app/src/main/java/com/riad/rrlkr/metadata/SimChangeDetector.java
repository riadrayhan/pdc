package com.riad.rrlkr.metadata;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SimChangeDetector {

    private final Context context;
    private final MetadataDatabase db;
    private static final String PREFS_NAME = "rrlkr_md_sim_prefs";
    private static final String KEY_SIM_FINGERPRINT = "saved_sim_fingerprint";

    public SimChangeDetector(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void checkAndRecordSimChange() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            String iccid = null;
            String carrier = tm.getSimOperatorName();
            String simOperator = tm.getSimOperator();
            String phoneNumber = "";
            int simSlot = 0;
            String subscriptionId = "";

            try { iccid = tm.getSimSerialNumber(); } catch (SecurityException ignored) {}

            try {
                SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                    if (subs != null && !subs.isEmpty()) {
                        for (SubscriptionInfo info : subs) {
                            simSlot = info.getSimSlotIndex();
                            subscriptionId = String.valueOf(info.getSubscriptionId());
                            CharSequence cn = info.getCarrierName();
                            if (cn != null && cn.length() > 0) carrier = cn.toString();
                            try {
                                String subIccid = info.getIccId();
                                if (subIccid != null && !subIccid.isEmpty()) iccid = subIccid;
                            } catch (Exception ignored) {}
                            try {
                                String num = info.getNumber();
                                if (num != null && !num.isEmpty()) phoneNumber = num;
                            } catch (Exception ignored) {}

                            String fp = buildFingerprint(iccid, subscriptionId, simOperator, simSlot);
                            recordSim(fp, iccid, phoneNumber, carrier, simOperator);
                        }
                        return;
                    }
                }
            } catch (SecurityException ignored) {}

            try {
                phoneNumber = tm.getLine1Number();
                if (phoneNumber == null) phoneNumber = "";
            } catch (SecurityException ignored) {}

            String fingerprint = buildFingerprint(iccid, "", simOperator, 0);
            if (fingerprint.isEmpty()) return;
            recordSim(fingerprint, iccid, phoneNumber, carrier, simOperator);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String buildFingerprint(String iccid, String subId, String simOp, int slot) {
        if (iccid != null && !iccid.isEmpty()) return iccid;
        if (simOp != null && !simOp.isEmpty()) return simOp + "_" + subId + "_" + slot;
        if (subId != null && !subId.isEmpty()) return "sub_" + subId;
        return "";
    }

    private void recordSim(String fingerprint, String iccid, String phoneNumber,
                           String carrier, String simOperator) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_SIM_FINGERPRINT, null);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String displayIccid = (iccid != null && !iccid.isEmpty()) ? iccid : "restricted_" + simOperator;

        if (saved == null) {
            db.insertSimChange("INITIAL", displayIccid, phoneNumber, carrier, timestamp);
            prefs.edit().putString(KEY_SIM_FINGERPRINT, fingerprint).apply();
        } else if (!saved.equals(fingerprint)) {
            db.insertSimChange(saved, displayIccid, phoneNumber, carrier, timestamp);
            prefs.edit().putString(KEY_SIM_FINGERPRINT, fingerprint).apply();
        }
    }
}
