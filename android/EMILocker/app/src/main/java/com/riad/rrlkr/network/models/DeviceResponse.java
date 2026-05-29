package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class DeviceResponse {
    
    @SerializedName("id")
    private String id;
    
    @SerializedName("imei")
    private String imei;
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("is_online")
    private boolean isOnline;
    
    @SerializedName("enrolled_at")
    private String enrolledAt;
    
    // Getters
    
    public String getId() {
        return id;
    }
    
    public String getImei() {
        return imei;
    }
    
    public String getStatus() {
        return status;
    }
    
    public boolean isOnline() {
        return isOnline;
    }
    
    public String getEnrolledAt() {
        return enrolledAt;
    }
}
