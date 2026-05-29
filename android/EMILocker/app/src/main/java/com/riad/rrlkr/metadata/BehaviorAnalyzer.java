package com.riad.rrlkr.metadata;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Computes behavioral metrics (call regularity, contact diversity, MFS activity, ...)
 * from the local SQLite tables. Run AFTER the other collectors so the inputs exist.
 */
public class BehaviorAnalyzer {

    private final Context context;
    private final MetadataDatabase db;

    public BehaviorAnalyzer(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void analyze() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        SQLiteDatabase sqlDb = db.getReadableDatabase();

        int totalCalls = 0, incomingCalls = 0, outgoingCalls = 0, missedCalls = 0;
        long totalDuration = 0;
        int nightCalls = 0, weekendCalls = 0;
        Set<String> uniqueContacts = new HashSet<>();
        Map<String, Integer> dailyCallCounts = new HashMap<>();

        try (Cursor c = sqlDb.rawQuery("SELECT number, type, date, duration FROM " +
                MetadataDatabase.TABLE_CALL_LOGS, null)) {
            while (c.moveToNext()) {
                totalCalls++;
                String number = c.getString(0), type = c.getString(1);
                String dateStr = c.getString(2), durationStr = c.getString(3);
                if (number != null) uniqueContacts.add(number);
                if ("INCOMING".equals(type)) incomingCalls++;
                else if ("OUTGOING".equals(type)) outgoingCalls++;
                else if ("MISSED".equals(type)) missedCalls++;
                try { totalDuration += Long.parseLong(durationStr); } catch (Exception ignored) {}
                try {
                    long ms = Long.parseLong(dateStr);
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(ms);
                    int hour = cal.get(Calendar.HOUR_OF_DAY);
                    int day = cal.get(Calendar.DAY_OF_WEEK);
                    if (hour >= 22 || hour < 6) nightCalls++;
                    if (day == Calendar.FRIDAY || day == Calendar.SATURDAY) weekendCalls++;
                    String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(ms));
                    dailyCallCounts.merge(dayKey, 1, Integer::sum);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        int totalSms = 0, sentSms = 0, receivedSms = 0;
        Set<String> uniqueSmsContacts = new HashSet<>();
        try (Cursor c = sqlDb.rawQuery("SELECT address, type FROM " + MetadataDatabase.TABLE_SMS, null)) {
            while (c.moveToNext()) {
                totalSms++;
                String address = c.getString(0), type = c.getString(1);
                if (address != null) uniqueSmsContacts.add(address);
                if ("SENT".equals(type)) sentSms++;
                else if ("RECEIVED".equals(type)) receivedSms++;
            }
        } catch (Exception ignored) {}

        int uniqueLocations = 0;
        try (Cursor c = sqlDb.rawQuery(
                "SELECT COUNT(DISTINCT ROUND(latitude,3) || ',' || ROUND(longitude,3)) FROM " +
                MetadataDatabase.TABLE_LOCATION, null)) {
            if (c.moveToFirst()) uniqueLocations = c.getInt(0);
        } catch (Exception ignored) {}

        int totalMfsTxns = 0;
        double totalMfsVolume = 0;
        try (Cursor c = sqlDb.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CAST(amount AS REAL)),0) FROM " +
                MetadataDatabase.TABLE_MOBILE_MONEY, null)) {
            if (c.moveToFirst()) { totalMfsTxns = c.getInt(0); totalMfsVolume = c.getDouble(1); }
        } catch (Exception ignored) {}

        int totalRecharges = 0;
        double totalRechargeAmount = 0;
        try (Cursor c = sqlDb.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(CAST(amount AS REAL)),0) FROM " +
                MetadataDatabase.TABLE_TELECOM_USAGE + " WHERE recharge_type='RECHARGE'", null)) {
            if (c.moveToFirst()) { totalRecharges = c.getInt(0); totalRechargeAmount = c.getDouble(1); }
        } catch (Exception ignored) {}

        double callRegularity = computeRegularity(dailyCallCounts);
        double inOutRatio = outgoingCalls > 0 ? (double) incomingCalls / outgoingCalls : 0;
        double nightRatio = totalCalls > 0 ? (double) nightCalls / totalCalls : 0;
        double weekendRatio = totalCalls > 0 ? (double) weekendCalls / totalCalls : 0;
        double avgCallDuration = totalCalls > 0 ? (double) totalDuration / totalCalls : 0;
        double contactDiversity = totalCalls > 0 ? (double) uniqueContacts.size() / totalCalls : 0;

        Set<String> allContacts = new HashSet<>(uniqueContacts);
        allContacts.addAll(uniqueSmsContacts);
        int networkSize = allContacts.size();

        double mfsActivityScore = Math.min(totalMfsTxns / 100.0, 1.0);
        double rechargeFreq = totalRecharges;

        db.insertBehaviorScore(
            totalCalls, incomingCalls, outgoingCalls, missedCalls,
            totalDuration, nightCalls, weekendCalls, uniqueContacts.size(),
            String.format(Locale.US, "%.2f", callRegularity),
            String.format(Locale.US, "%.2f", inOutRatio),
            String.format(Locale.US, "%.3f", nightRatio),
            String.format(Locale.US, "%.3f", weekendRatio),
            String.format(Locale.US, "%.1f", avgCallDuration),
            String.format(Locale.US, "%.3f", contactDiversity),
            totalSms, sentSms, receivedSms, uniqueSmsContacts.size(),
            networkSize, uniqueLocations,
            totalMfsTxns, String.format(Locale.US, "%.2f", totalMfsVolume),
            totalRecharges, String.format(Locale.US, "%.2f", totalRechargeAmount),
            String.format(Locale.US, "%.2f", mfsActivityScore),
            String.format(Locale.US, "%.1f", rechargeFreq),
            timestamp);
    }

    private double computeRegularity(Map<String, Integer> dailyCounts) {
        if (dailyCounts.isEmpty()) return 0;
        double sum = 0;
        for (int count : dailyCounts.values()) sum += count;
        double mean = sum / dailyCounts.size();
        double variance = 0;
        for (int count : dailyCounts.values()) variance += Math.pow(count - mean, 2);
        variance /= dailyCounts.size();
        double stdDev = Math.sqrt(variance);
        return mean > 0 ? stdDev / mean : 0;
    }
}
