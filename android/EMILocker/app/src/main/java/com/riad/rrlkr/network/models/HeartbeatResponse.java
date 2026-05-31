package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class HeartbeatResponse {
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("message")
    private String message;
    
    @SerializedName("device_id")
    private String deviceId;
    
    public String getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
}
