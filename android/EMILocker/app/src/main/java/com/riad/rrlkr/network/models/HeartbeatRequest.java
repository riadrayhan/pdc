package com.riad.rrlkr.network.models;

import com.google.gson.annotations.SerializedName;

public class HeartbeatRequest {
    
    @SerializedName("device_id")
    private String deviceId;
    
    @SerializedName("imei")
    private String imei;
    
    @SerializedName("imei2")
    private String imei2;
    
    @SerializedName("android_id")
    private String androidId;
    
    @SerializedName("fcm_token")
    private String fcmToken;
    
    @SerializedName("battery_level")
    private Integer batteryLevel;
    
    @SerializedName("is_charging")
    private Boolean isCharging;
    
    @SerializedName("network_type")
    private String networkType;
    
    @SerializedName("app_version")
    private String appVersion;
    
    @SerializedName("device_name")
    private String deviceName;
    
    @SerializedName("brand")
    private String brand;
    
    @SerializedName("manufacturer")
    private String manufacturer;
    
    @SerializedName("device_model")
    private String deviceModel;
    
    @SerializedName("serial_number")
    private String serialNumber;
    
    @SerializedName("android_version")
    private String androidVersion;
    
    @SerializedName("is_device_owner")
    private Boolean isDeviceOwner;
    
    @SerializedName("is_admin_active")
    private Boolean isAdminActive;
    
    // Getters and Setters
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getImei() { return imei; }
    public void setImei(String imei) { this.imei = imei; }
    
    public String getImei2() { return imei2; }
    public void setImei2(String imei2) { this.imei2 = imei2; }
    
    public String getAndroidId() { return androidId; }
    public void setAndroidId(String androidId) { this.androidId = androidId; }
    
    public String getFcmToken() { return fcmToken; }
    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }
    
    public Integer getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }
    
    public Boolean getCharging() { return isCharging; }
    public void setCharging(Boolean charging) { isCharging = charging; }
    
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
    
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    
    public String getAndroidVersion() { return androidVersion; }
    public void setAndroidVersion(String androidVersion) { this.androidVersion = androidVersion; }
    
    public Boolean getDeviceOwner() { return isDeviceOwner; }
    public void setDeviceOwner(Boolean deviceOwner) { isDeviceOwner = deviceOwner; }
    
    public Boolean getAdminActive() { return isAdminActive; }
    public void setAdminActive(Boolean adminActive) { isAdminActive = adminActive; }
}
