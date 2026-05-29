package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class CommandAck {
    
    @SerializedName("command_id")
    private String commandId;
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("error_message")
    private String errorMessage;
    
    // Getters and Setters
    
    public String getCommandId() {
        return commandId;
    }
    
    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
