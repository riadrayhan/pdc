package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class CommandResponse {
    
    @SerializedName("id")
    private String id;
    
    @SerializedName("command_type")
    private String commandType;
    
    @SerializedName("payload")
    private Map<String, Object> payload;
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("created_at")
    private String createdAt;
    
    // Getters
    
    public String getId() {
        return id;
    }
    
    public String getCommandType() {
        return commandType;
    }
    
    public Map<String, Object> getPayload() {
        return payload;
    }
    
    public String getStatus() {
        return status;
    }
    
    public String getCreatedAt() {
        return createdAt;
    }
}
