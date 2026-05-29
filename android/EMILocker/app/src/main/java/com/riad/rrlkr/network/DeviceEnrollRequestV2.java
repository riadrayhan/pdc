package com.riad.rrlkr.network;

import com.google.gson.annotations.SerializedName;

/**
 * Request model for device enrollment V2
 * Sends complete device fingerprint to server
 * Uses @SerializedName to send snake_case JSON matching backend schema
 */
public class DeviceEnrollRequestV2 {

    // Primary identifiers (won't change after factory reset)
    private String imei;
    private String imei2;
    
    @SerializedName("serial_number")
    private String serialNumber;
    
    @SerializedName("persistent_device_id")
    private String persistentDeviceId;

    // Secondary identifiers (may change after reset)
    @SerializedName("android_id")
    private String androidId;
    
    @SerializedName("fcm_token")
    private String fcmToken;

    // Device information
    private String manufacturer;
    private String brand;
    private String model;
    private String device;
    private String product;
    private String board;
    private String hardware;

    // Software information
    @SerializedName("android_version")
    private String androidVersion;
    
    @SerializedName("sdk_version")
    private int sdkVersion;
    
    @SerializedName("build_id")
    private String buildId;
    
    @SerializedName("build_fingerprint")
    private String buildFingerprint;

    // App information
    @SerializedName("app_version")
    private String appVersion;
    
    @SerializedName("is_device_owner")
    private boolean isDeviceOwner;
    
    @SerializedName("is_admin_active")
    private boolean isAdminActive;

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

    public String getPersistentDeviceId() {
        return persistentDeviceId;
    }

    public void setPersistentDeviceId(String persistentDeviceId) {
        this.persistentDeviceId = persistentDeviceId;
    }

    public String getAndroidId() {
        return androidId;
    }

    public void setAndroidId(String androidId) {
        this.androidId = androidId;
    }

    public String getFcmToken() {
        return fcmToken;
    }

    public void setFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String board) {
        this.board = board;
    }

    public String getHardware() {
        return hardware;
    }

    public void setHardware(String hardware) {
        this.hardware = hardware;
    }

    public String getAndroidVersion() {
        return androidVersion;
    }

    public void setAndroidVersion(String androidVersion) {
        this.androidVersion = androidVersion;
    }

    public int getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(int sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public String getBuildFingerprint() {
        return buildFingerprint;
    }

    public void setBuildFingerprint(String buildFingerprint) {
        this.buildFingerprint = buildFingerprint;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public boolean isDeviceOwner() {
        return isDeviceOwner;
    }

    public void setDeviceOwner(boolean deviceOwner) {
        isDeviceOwner = deviceOwner;
    }

    public boolean isAdminActive() {
        return isAdminActive;
    }

    public void setAdminActive(boolean adminActive) {
        isAdminActive = adminActive;
    }
}
