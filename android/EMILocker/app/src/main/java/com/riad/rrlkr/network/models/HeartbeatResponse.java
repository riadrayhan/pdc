package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class HeartbeatResponse {
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("message")
    private String message;
    
    public String getStatus() {
        return status;
    }
    
    public String getMessage() {
        return message;
    }
}
