package com.riad.rrlkr.metadata;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records best-known location with dwell time. Uses Android LocationManager
 * (last known fix) so it doesn't add play-services-location as a dependency.
 */
public class LocationDwellCollector {

    private final Context context;
    private final MetadataDatabase db;
    private static final float DWELL_RADIUS_METERS = 100f;
    private static final String PREFS_NAME = "rrlkr_md_location_dwell";

    public LocationDwellCollector(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void collect() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l == null) continue;
                    if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
                } catch (SecurityException ignored) {}
            }
            if (best != null) process(best);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void process(Location location) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date(location.getTime()));
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        float accuracy = location.getAccuracy();
        String addr = reverseGeocode(lat, lng);

        android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double lastLat = Double.longBitsToDouble(prefs.getLong("last_lat", 0));
        double lastLng = Double.longBitsToDouble(prefs.getLong("last_lng", 0));
        long lastTs = prefs.getLong("last_timestamp", 0);

        long now = System.currentTimeMillis();
        long dwellMinutes = 0;
        boolean same = false;
        if (lastTs > 0) {
            float[] results = new float[1];
            Location.distanceBetween(lastLat, lastLng, lat, lng, results);
            if (results[0] < DWELL_RADIUS_METERS) {
                dwellMinutes = (now - lastTs) / 60000;
                same = true;
            }
        }

        String locationType = classifyLocation(timestamp);
        int visitCount = db.getLocationVisitCount(lat, lng, DWELL_RADIUS_METERS);

        db.insertLocationDwell(lat, lng, accuracy, addr, timestamp,
            dwellMinutes, locationType, visitCount + 1, same ? "DWELL" : "ARRIVAL");

        // Also write to plain location table for the dashboard map
        db.insertLocation(lat, lng, accuracy, timestamp, addr);

        prefs.edit()
            .putLong("last_lat", Double.doubleToLongBits(lat))
            .putLong("last_lng", Double.doubleToLongBits(lng))
            .putLong("last_timestamp", same ? lastTs : now)
            .apply();
    }

    private String classifyLocation(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date date = sdf.parse(timestamp);
            if (date == null) return "UNKNOWN";
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(date);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
            boolean isWeekend = (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.FRIDAY);
            if (hour >= 22 || hour < 6) return "NIGHT_HOME";
            if (hour >= 9 && hour < 17 && !isWeekend) return "WORK_HOURS";
            if (isWeekend) return "WEEKEND";
            if (hour >= 6 && hour < 9) return "MORNING_COMMUTE";
            if (hour >= 17 && hour < 22) return "EVENING";
            return "OTHER";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String reverseGeocode(double lat, double lng) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                if (addr.getSubLocality() != null) sb.append(addr.getSubLocality()).append(", ");
                if (addr.getLocality() != null) sb.append(addr.getLocality()).append(", ");
                if (addr.getSubAdminArea() != null) sb.append(addr.getSubAdminArea()).append(", ");
                if (addr.getAdminArea() != null) sb.append(addr.getAdminArea());
                String result = sb.toString();
                if (result.endsWith(", ")) result = result.substring(0, result.length() - 2);
                if (result.isEmpty() && addr.getMaxAddressLineIndex() >= 0) {
                    result = addr.getAddressLine(0);
                }
                return result;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
