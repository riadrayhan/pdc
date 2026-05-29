# EMI Locker - Device Setup Guide

## ?? How Factory Reset Protection Works

EMI Locker uses multiple layers of protection to ensure the app cannot be easily removed:

### Device Fingerprint System
When a device is enrolled, we capture:
- **IMEI** - Unique to the device, survives factory reset
- **IMEI 2** - For dual SIM devices
- **Serial Number** - Hardware identifier
- **Persistent Device ID** - Hash of hardware identifiers

After factory reset:
1. Device connects to internet
2. Our system app detects network connectivity
3. Sends device fingerprint to server
4. Server matches IMEI/Serial with database
5. If match found ? triggers app reinstallation or lock

---

## ?? Setup Options

### Option 1: Zero-Touch Enrollment (Recommended for Enterprise)

Zero-Touch Enrollment is a Google program that allows automatic device provisioning. **This is the most reliable method** for ensuring the app survives factory resets.

#### Requirements:
- Devices must be purchased from authorized resellers
- You need a Zero-Touch portal account

#### Setup Steps:
1. **Register for Zero-Touch**: Contact Google or authorized reseller
2. **Create Configuration**:
   ```json
   {
     "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": 
       "com.riad.rrlkr/.admin.EMIDeviceAdminReceiver",
     "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": 
       "https://your-server.com/downloads/emi_locker.apk",
     "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
       "server_url": "https://your-api-server.com"
     }
   }
   ```
3. **Link Devices**: Add device IMEIs to your Zero-Touch portal
4. **Factory Reset Device**: It will automatically enroll and install your app

---

### Option 2: QR Code Provisioning

For new devices during initial setup:

#### Generate QR Code:
```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": 
    "com.riad.rrlkr/.admin.EMIDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": 
    "https://your-server.com/downloads/emi_locker.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": 
    "YOUR_APK_SHA256_CHECKSUM",
  "android.app.extra.PROVISIONING_WIFI_SSID": "YourWiFiName",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "YourWiFiPassword",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true
}
```

#### How to Use:
1. Factory reset the device
2. On welcome screen, tap 6 times on the logo
3. Camera will open to scan QR code
4. Scan the generated QR code
5. Device will automatically provision and install app

---

### Option 3: ADB Setup (Development/Testing)

For development or when you have physical access:

```bash
# Make sure no Google accounts are on the device first
adb shell pm list users

# Set device owner
adb shell dpm set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver

# Verify
adb shell dumpsys device_policy
```

**Note**: This must be done on a freshly reset device with no accounts added.

---

### Option 4: Samsung Knox (Samsung Devices Only)

For Samsung devices, Knox Mobile Enrollment provides similar functionality:

1. Register for Samsung Knox
2. Upload device IMEIs
3. Configure MDM settings with your APK
4. Devices auto-enroll after factory reset

---

## ?? Admin Panel: Device Management

### When Device is Enrolled:
The admin panel shows:
- IMEI (Primary)
- IMEI (Secondary) - if dual SIM
- Serial Number
- Device Model
- Android Version
- Online/Offline Status
- Lock Status

### After Factory Reset:
When a previously enrolled device comes back online:
1. System detects the IMEI/Serial match
2. Device status shows "Needs Re-enrollment"
3. If payment is overdue ? Device is auto-locked
4. App can be pushed for reinstallation

---

## ?? Important Limitations

### What We CAN Do:
? Track device by IMEI/Serial after reset
? Lock device when it connects to internet
? Push app installation (requires user interaction)
? Prevent app uninstall (when Device Owner)
? Survive soft reset/reboot

### What We CANNOT Do Without Zero-Touch:
? Force app install without user interaction after reset
? Prevent factory reset completely
? Track device without internet

### Recommendations:
1. **Use Zero-Touch Enrollment** for best protection
2. **Samsung Knox** for Samsung-only deployments
3. **Keep APK size small** for faster downloads
4. **Use multiple identification methods** (IMEI + Serial + etc.)

---

## ?? API Endpoints for Device Fingerprint

### Check Device Status
```
POST /api/v1/enrollment/check-status
```
Request:
```json
{
  "imei": "123456789012345",
  "serial_number": "ABC123XYZ",
  "persistent_device_id": "sha256hash..."
}
```
Response:
```json
{
  "known_device": true,
  "needs_re_enrollment": true,
  "should_lock": false,
  "apk_url": "https://server.com/emi_locker.apk",
  "device_id": 123,
  "customer_name": "John Doe"
}
```

### Enroll Device
```
POST /api/v1/enrollment/enroll
```
Request: (Full device fingerprint)
```json
{
  "imei": "123456789012345",
  "imei2": "123456789012346",
  "serial_number": "ABC123XYZ",
  "persistent_device_id": "sha256hash...",
  "android_id": "abc123",
  "manufacturer": "Samsung",
  "model": "Galaxy A52",
  "android_version": "12",
  "fcm_token": "...",
  "is_device_owner": true
}
```

---

## ?? Troubleshooting

### Device Not Auto-Enrolling After Reset
1. Check if IMEI was stored correctly before reset
2. Verify device has internet connection
3. Check server logs for incoming requests
4. Verify APK download URL is accessible

### App Gets Uninstalled
1. Ensure Device Owner mode was set correctly
2. Check if user did factory reset
3. Without Zero-Touch, manual intervention needed

### Lock Not Working
1. Check if FCM token is valid
2. Verify device is online
3. Check command delivery status in admin panel
