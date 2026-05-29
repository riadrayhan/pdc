package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class DeviceEnrollRequest {
    
    @SerializedName("imei")
    private String imei;
    
    @SerializedName("imei2")
    private String imei2;
    
    @SerializedName("serial_number")
    private String serialNumber;
    
    @SerializedName("device_model")
    private String deviceModel;
    
    @SerializedName("manufacturer")
    private String manufacturer;
    
    @SerializedName("android_version")
    private String androidVersion;
    
    @SerializedName("sdk_version")
    private String sdkVersion;
    
    @SerializedName("fcm_token")
    private String fcmToken;
    
    // Getters and Setters
    
    public String getImei() {
        return imei;
    }
    
    public void setImei(String imei) {
        this.imei = imei;
    }
    
    public String getImei2() {
        return imei2;
    }
    
    public void setImei2(String imei2) {
        this.imei2 = imei2;
    }
    
    public String getSerialNumber() {
        return serialNumber;
    }
    
    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }
    
    public String getDeviceModel() {
        return deviceModel;
    }
    
    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }
    
    public String getManufacturer() {
        return manufacturer;
    }
    
    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }
    
    public String getAndroidVersion() {
        return androidVersion;
    }
    
    public void setAndroidVersion(String androidVersion) {
        this.androidVersion = androidVersion;
    }
    
    public String getSdkVersion() {
        return sdkVersion;
    }
    
    public void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }
    
    public String getFcmToken() {
        return fcmToken;
    }
    
    public void setFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }
}
