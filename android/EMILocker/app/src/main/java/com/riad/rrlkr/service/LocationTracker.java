package com.riad.rrlkr.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.riad.rrlkr.network.ApiClient;
import com.riad.rrlkr.network.ApiService;
import com.riad.rrlkr.util.PreferenceManager;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * GPS Location Tracker - Gets current device location and reports to server
 * Called when admin sends GPS_TRACK command
 */
public class LocationTracker {

    private static final String TAG = "LocationTracker";
    private static final long LOCATION_TIMEOUT = 30000; // 30 seconds timeout

    private final Context context;
    private final ApiService apiService;
    private final PreferenceManager preferenceManager;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Handler handler;
    private boolean locationReceived = false;

    public LocationTracker(Context context) {
        this.context = context;
        this.apiService = ApiClient.getApiService();
        this.preferenceManager = new PreferenceManager(context);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Request current GPS location and send it to the server
     */
    public void trackAndReport() {
        Log.i(TAG, "Starting GPS location tracking...");

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            reportLocationError("Location permission not granted");
            return;
        }

        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.e(TAG, "LocationManager not available");
            reportLocationError("LocationManager not available");
            return;
        }

        locationReceived = false;

        // Try to get last known location first
        Location lastKnown = getLastKnownLocation();
        if (lastKnown != null) {
            Log.i(TAG, "Using last known location: " + lastKnown.getLatitude() + ", " + lastKnown.getLongitude());
            reportLocation(lastKnown);
        }

        // Also request fresh location update
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (!locationReceived) {
                    locationReceived = true;
                    Log.i(TAG, "Fresh location received: " + location.getLatitude() + ", " + location.getLongitude());
                    reportLocation(location);
                    stopTracking();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
                Log.w(TAG, "Location provider disabled: " + provider);
            }
        };

        // Request location from all available providers
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
                Log.d(TAG, "GPS provider requested");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting GPS provider", e);
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
                Log.d(TAG, "Network provider requested");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting Network provider", e);
        }

        // Timeout - stop after 30 seconds
        handler.postDelayed(() -> {
            if (!locationReceived) {
                Log.w(TAG, "Location timeout - no fresh location received");
                stopTracking();
                if (lastKnown == null) {
                    reportLocationError("Location timeout - could not get GPS fix");
                }
            }
        }, LOCATION_TIMEOUT);
    }

    private Location getLastKnownLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        Location bestLocation = null;

        try {
            Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (gpsLocation != null && networkLocation != null) {
                bestLocation = gpsLocation.getTime() > networkLocation.getTime() ? gpsLocation : networkLocation;
            } else if (gpsLocation != null) {
                bestLocation = gpsLocation;
            } else {
                bestLocation = networkLocation;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting last known location", e);
        }

        return bestLocation;
    }

    private void reportLocation(Location location) {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID - cannot report location");
            return;
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        String address = getAddressFromLocation(latitude, longitude);

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("latitude", String.valueOf(latitude));
        locationData.put("longitude", String.valueOf(longitude));
        locationData.put("address", address != null ? address : "");
        locationData.put("accuracy", String.valueOf(location.getAccuracy()));
        locationData.put("provider", location.getProvider());

        Log.i(TAG, "Reporting location to server: " + latitude + ", " + longitude);

        apiService.reportLocation(deviceId, locationData).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Location reported successfully");
                } else {
                    Log.e(TAG, "Failed to report location: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error reporting location", t);
            }
        });
    }

    private void reportLocationError(String error) {
        String deviceId = preferenceManager.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) return;

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("latitude", "0");
        locationData.put("longitude", "0");
        locationData.put("address", "Error: " + error);

        apiService.reportLocation(deviceId, locationData).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                Log.d(TAG, "Location error reported");
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "Error reporting location error", t);
            }
        });
    }

    private String getAddressFromLocation(double latitude, double longitude) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= addr.getMaxAddressLineIndex(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(addr.getAddressLine(i));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Geocoder error", e);
        }
        return null;
    }

    private void stopTracking() {
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
                Log.d(TAG, "Location tracking stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location tracking", e);
        }
    }
}
