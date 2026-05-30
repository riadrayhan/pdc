"""One-time AMAPI setup script - creates enterprise, policy, and enrollment token."""
import json
import sys
from google.oauth2 import service_account
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/androidmanagement"]
PROJECT_ID = "blooger-project"
CALLBACK_URL = "https://riadrayhan111-rr-locker-api.hf.space/api/v1/amapi/callback"
SERVICE_ACCOUNT_FILE = "service-account.json"

# EMI Locker config
EMI_LOCKER_PACKAGE = "com.riad.rrlkr"
APK_URL = "https://riadrayhan111-rr-locker-api.hf.space/api/v1/zte/apk"

def get_service():
    creds = service_account.Credentials.from_service_account_file(
        SERVICE_ACCOUNT_FILE, scopes=SCOPES
    )
    return build("androidmanagement", "v1", credentials=creds)

def step1_create_enterprise():
    """Create a project-bound enterprise (no browser/Google account needed)."""
    service = get_service()
    
    print("Step 1: Creating project-bound enterprise...")
    print("(No browser signup needed - enterprise is bound to the GCP project)\n")
    
    enterprise = service.enterprises().create(
        projectId=PROJECT_ID,
        agreementAccepted=True,
        body={
            "enterpriseDisplayName": "RR Locker EMI Finance",
        }
    ).execute()
    
    enterprise_name = enterprise.get("name", "")
    print(f"=== ENTERPRISE CREATED ===")
    print(f"Enterprise name: {enterprise_name}")
    print(f"Full response: {json.dumps(enterprise, indent=2)}")
    return enterprise_name

def step2_create_policy(enterprise_name):
    """Create device management policy."""
    service = get_service()
    
    print(f"\nStep 3: Creating policy for {enterprise_name}...")
    policy = {
        # Allow installing apps from unknown sources (our APK server)
        "advancedSecurityOverrides": {
            "untrustedAppsPolicy": "ALLOW_INSTALL_DEVICE_WIDE",
            "developerSettings": "DEVELOPER_SETTINGS_ALLOWED",
        },
        "factoryResetDisabled": True,
        "safeBootDisabled": True,
        "screenCaptureDisabled": True,
        "addUserDisabled": True,
        "removeUserDisabled": True,
        "modifyAccountsDisabled": False,
        "systemUpdate": {
            "type": "WINDOWED",
            "startMinutes": 120,
            "endMinutes": 300
        },
        "skipFirstUseHintsEnabled": True,
        "adjustVolumeDisabled": False,
        "funDisabled": True,
        "networkEscapeHatchEnabled": True,
        "playStoreMode": "BLACKLIST",
    }
    
    result = service.enterprises().policies().patch(
        name=f"{enterprise_name}/policies/emi-locker-policy",
        body=policy
    ).execute()
    
    print(f"Policy created: {result.get('name', '')}")
    return result

def step3_create_enrollment_token(enterprise_name):
    """Generate enrollment token for QR code."""
    service = get_service()
    
    print(f"\nStep 4: Creating enrollment token...")
    token = service.enterprises().enrollmentTokens().create(
        parent=enterprise_name,
        body={
            "policyName": f"{enterprise_name}/policies/emi-locker-policy",
            "duration": "86400s",
            "allowPersonalUsage": "PERSONAL_USAGE_DISALLOWED",
            "oneTimeOnly": False,
        }
    ).execute()
    
    token_value = token.get("value", "")
    print(f"Token value: {token_value}")
    print(f"Token name: {token.get('name', '')}")
    print(f"Expiry: {token.get('expirationTimestamp', '')}")
    
    # Generate QR payload
    qr_payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": 
            "com.google.android.apps.work.clouddpc/.receivers.CloudDeviceAdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": 
            "I5YvS0O5hXY46mb01BlRjq4oJJGs2kuUcHvVkAPEXlg",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": 
            "https://play.google.com/managed/downloadManagingApp?identifier=setup",
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
            "com.google.android.apps.work.clouddpc.EXTRA_ENROLLMENT_TOKEN": token_value
        },
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    }
    
    qr_string = json.dumps(qr_payload, separators=(",", ":"))
    print(f"\n=== QR CODE STRING ===")
    print(qr_string)
    print(f"\n=== QR STRING LENGTH: {len(qr_string)} chars ===")
    
    return token_value, qr_string

if __name__ == "__main__":
    import os
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    try:
        # Use existing enterprise if available
        enterprise_name = "enterprises/LC02fkc86a"
        print(f"Using existing enterprise: {enterprise_name}")
        
        # Step 2: Update policy
        step2_create_policy(enterprise_name)
        
        # Step 3: Create enrollment token + QR
        token_value, qr_string = step3_create_enrollment_token(enterprise_name)
        
        print("\n" + "=" * 60)
        print("SETUP COMPLETE!")
        print("=" * 60)
        print(f"\nEnterprise: {enterprise_name}")
        print(f"\nSet these env vars on your host:")
        print(f"  AMAPI_ENTERPRISE_NAME = {enterprise_name}")
        print(f"  AMAPI_PROJECT_ID = {PROJECT_ID}")
        print(f"\nQR string is ready for scanning!")
        
    except Exception as e:
        print(f"\nERROR: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
