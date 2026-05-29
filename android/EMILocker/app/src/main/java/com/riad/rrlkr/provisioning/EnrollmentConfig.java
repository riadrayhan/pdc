package com.riad.rrlkr.provisioning;

import android.content.Context;

/**
 * Zero-Touch / Knox Enrollment Configuration
 * 
 * à¦à¦‡ feature à¦•à¦¾à¦œ à¦•à¦°à¦¤à§‡ à¦¹à¦²à§‡ à¦†à¦ªà¦¨à¦¾à¦•à§‡ à¦¨à¦¿à¦šà§‡à¦° à¦•à¦¾à¦œà¦—à§à¦²à§‹ à¦•à¦°à¦¤à§‡ à¦¹à¦¬à§‡:
 * 
 * ==========================================
 * OPTION 1: Google Zero-Touch Enrollment
 * ==========================================
 * 
 * à¦à¦Ÿà¦¾ à¦•à¦¿à¦­à¦¾à¦¬à§‡ à¦•à¦¾à¦œ à¦•à¦°à§‡:
 * 1. à¦†à¦ªà¦¨à¦¿ Google Zero-Touch portal à¦ register à¦•à¦°à¦¬à§‡à¦¨
 * 2. Device IMEI à¦—à§à¦²à§‹ portal à¦ add à¦•à¦°à¦¬à§‡à¦¨
 * 3. à¦†à¦ªà¦¨à¦¾à¦° APK à¦à¦° configuration à¦¦à¦¿à¦¬à§‡à¦¨
 * 4. à¦¯à¦–à¦¨ à¦•à§‡à¦‰ phone reset à¦•à¦°à¦¬à§‡, Google automatically à¦†à¦ªà¦¨à¦¾à¦° app install à¦•à¦°à¦¬à§‡
 * 
 * Requirements:
 * - Device must be purchased from authorized reseller
 * - Devices must support Zero-Touch (Android 8.0+)
 * 
 * Apply here: https://partner.android.com/zerotouch
 * 
 * ==========================================
 * OPTION 2: Samsung Knox Mobile Enrollment
 * ==========================================
 * 
 * Samsung devices à¦à¦° à¦œà¦¨à§à¦¯:
 * 1. Samsung Knox portal à¦ register à¦•à¦°à§à¦¨
 * 2. Device IMEI add à¦•à¦°à§à¦¨
 * 3. MDM configuration set à¦•à¦°à§à¦¨
 * 4. Reset à¦à¦° à¦ªà¦°à¦“ app auto-install à¦¹à¦¬à§‡
 * 
 * Apply here: https://www.samsungknox.com/
 * 
 * ==========================================
 * OPTION 3: Custom ROM / System App
 * ==========================================
 * 
 * à¦†à¦ªà¦¨à¦¿ à¦¯à¦¦à¦¿ phone sell à¦•à¦°à§‡à¦¨:
 * 1. Phone à¦ custom ROM flash à¦•à¦°à§à¦¨
 * 2. à¦†à¦ªà¦¨à¦¾à¦° app à¦•à§‡ system app à¦¹à¦¿à¦¸à§‡à¦¬à§‡ include à¦•à¦°à§à¦¨
 * 3. System app factory reset survive à¦•à¦°à§‡
 * 
 * ==========================================
 * OPTION 4: OEM Partnership
 * ==========================================
 * 
 * Phone manufacturer à¦à¦° à¦¸à¦¾à¦¥à§‡ deal à¦•à¦°à§à¦¨:
 * - Xiaomi, Samsung, Realme etc.
 * - à¦¤à¦¾à¦°à¦¾ à¦†à¦ªà¦¨à¦¾à¦° app pre-install à¦•à¦°à§‡ à¦¦à¦¿à¦¬à§‡
 * - EMI financing companies à¦à¦­à¦¾à¦¬à§‡à¦‡ à¦•à¦°à§‡
 * 
 */
public class EnrollmentConfig {
    
    /**
     * Zero-Touch Configuration JSON
     * à¦à¦Ÿà¦¾ Zero-Touch portal à¦ use à¦•à¦°à¦¬à§‡à¦¨
     * 
     * NOTE: Samsung QR provisioning does NOT support admin extras (nested JSON).
     * Use the QR code from the admin panel which generates Samsung-compatible payload.
     * 
     * For Google Zero-Touch portal (non-Samsung), admin extras are supported.
     */
    public static final String ZERO_TOUCH_CONFIG =
        "{\n" +
        "    \"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\": \n" +
        "        \"com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver\",\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION\": \n" +
        "        \"https://rr-locker-api.onrender.com/api/v1/zte/apk\",\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM\": \n" +
        "        \"nyEi8RTwqxNUMWmBjDp0LXJ1CxXk2Ya0ABoo_UCpkEk\",\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_SKIP_ENCRYPTION\": true,\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED\": true,\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_ALLOW_OFFLINE\": true,\n" +
        "    \n" +
        "    \"android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE\": 2\n" +
        "}";

    /**
     * Samsung Knox Mobile Enrollment (KME) Configuration
     * 
     * Setup at: https://samsungknox.com/
     * 
     * Steps:
     * 1. Register at Samsung Knox portal
     * 2. Create MDM Profile with this configuration
     * 3. Add Samsung device IMEIs to portal
     * 4. Assign MDM Profile to devices
     * 5. Factory reset Samsung device â†’ auto-installs app via Knox
     * 
     * Compatible with:
     * - Samsung Galaxy S6+ / Note 5+ / A/J/M/F Series
     * - Knox 2.4+ (One UI 2-6)
     * - Android 10-14 (One UI 2-6)
     */
    public static final String KNOX_CONFIG =
        "{\n" +
        "    \"mdm\": {\n" +
        "        \"packageName\": \"com.riad.rrlkr\",\n" +
        "        \"downloadUrl\": \"https://rr-locker-api.onrender.com/api/v1/zte/apk\",\n" +
        "        \"deviceAdminComponentName\": \"com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver\",\n" +
        "        \"signature\": \"nyEi8RTwqxNUMWmBjDp0LXJ1CxXk2Ya0ABoo_UCpkEk\"\n" +
        "    },\n" +
        "    \"settings\": {\n" +
        "        \"factoryResetProtection\": true,\n" +
        "        \"allowUnenroll\": false,\n" +
        "        \"systemAppsEnabled\": true,\n" +
        "        \"skipEncryption\": true\n" +
        "    }\n" +
        "}";
}
