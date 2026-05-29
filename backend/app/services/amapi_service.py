"""
Android Management API (AMAPI) Service

Uses Google's Android Management API to provision devices with Google's trusted
Cloud DPC (com.google.android.apps.work.clouddpc). This DPC is pre-approved by
Play Protect and downloaded from Play Store during QR provisioning, completely
bypassing the "app blocked to protect your device" warning.

Flow:
1. Admin creates an enterprise (one-time setup)
2. Backend creates a policy that force-installs the EMI Locker app
3. Backend generates enrollment tokens
4. QR code contains Google DPC + enrollment token
5. Device scans QR â†’ downloads Google DPC from Play Store (trusted, no block)
6. Google DPC applies policy â†’ force-installs EMI Locker from backend URL
7. EMI Locker starts â†’ auto-enrolls via existing ZTE pipeline
"""

import os
import json
import base64
import logging
from typing import Optional
from google.oauth2 import service_account
from googleapiclient.discovery import build

from app.core.config import settings

logger = logging.getLogger(__name__)

SCOPES = ["https://www.googleapis.com/auth/androidmanagement"]

# Google's Cloud DPC - trusted by Play Protect on all Android devices
CLOUD_DPC_COMPONENT = "com.google.android.apps.work.clouddpc/.receivers.CloudDeviceAdminReceiver"
CLOUD_DPC_SIGNATURE = "I5YvS0O5hXY46mb01BlRjq4oJJGs2kuUcHvVkAPEXlg"
CLOUD_DPC_DOWNLOAD = "https://play.google.com/managed/downloadManagingApp?identifier=setup"

# EMI Locker package
EMI_LOCKER_PACKAGE = "com.riad.rrlkr"

# APK hosting URL for force-install
APK_INSTALL_URL = "https://rr-locker-api.onrender.com/api/v1/zte/apk"


def _get_credentials():
    """Get Google service account credentials from env or file."""
    cred_value = settings.AMAPI_SERVICE_ACCOUNT_JSON
    
    # Try well-known local file path first (for local dev and Render)
    local_paths = [
        os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "service-account.json"),
        "service-account.json",
    ]
    for path in local_paths:
        if os.path.isfile(path):
            logger.info(f"Using service account from file: {path}")
            return service_account.Credentials.from_service_account_file(path, scopes=SCOPES)
    
    if not cred_value:
        raise ValueError(
            "AMAPI_SERVICE_ACCOUNT_JSON not configured. "
            "Set it to the base64-encoded service account JSON or a file path."
        )

    # Try as file path first
    if os.path.isfile(cred_value):
        return service_account.Credentials.from_service_account_file(
            cred_value, scopes=SCOPES
        )

    # Try as base64-encoded JSON
    try:
        decoded = base64.b64decode(cred_value)
        info = json.loads(decoded)
        return service_account.Credentials.from_service_account_info(
            info, scopes=SCOPES
        )
    except Exception:
        pass

    # Try as raw JSON string
    try:
        info = json.loads(cred_value)
        return service_account.Credentials.from_service_account_info(
            info, scopes=SCOPES
        )
    except Exception:
        raise ValueError(
            "AMAPI_SERVICE_ACCOUNT_JSON is not a valid file path, "
            "base64-encoded JSON, or raw JSON string."
        )


def _get_service():
    """Build the Android Management API service client."""
    credentials = _get_credentials()
    return build("androidmanagement", "v1", credentials=credentials)


def is_configured() -> bool:
    """Check if AMAPI credentials are configured (env var or local file)."""
    if settings.AMAPI_SERVICE_ACCOUNT_JSON and settings.AMAPI_PROJECT_ID:
        return True
    # Also check for local service-account.json file
    if settings.AMAPI_PROJECT_ID:
        local_paths = [
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "service-account.json"),
            "service-account.json",
        ]
        for path in local_paths:
            if os.path.isfile(path):
                return True
    return False


def get_enterprise_name() -> Optional[str]:
    """Get the current enterprise name if set."""
    return settings.AMAPI_ENTERPRISE_NAME or None


def create_enterprise() -> dict:
    """Create a new Android Enterprise and return its details.
    
    Creates a project-bound enterprise (no browser signup needed).
    The enterprise name must be saved in AMAPI_ENTERPRISE_NAME for subsequent operations.
    """
    service = _get_service()
    project_id = settings.AMAPI_PROJECT_ID

    enterprise = service.enterprises().create(
        projectId=project_id,
        agreementAccepted=True,
        body={
            "enterpriseDisplayName": "RR Locker EMI Finance",
        }
    ).execute()

    enterprise_name = enterprise.get("name", "")
    logger.info(f"Enterprise created: {enterprise_name}")
    return {
        "enterprise_name": enterprise_name,
        "enterprise": enterprise
    }


def create_policy(enterprise_name: str, policy_name: str = "emi-locker-policy") -> dict:
    """Create or update the device policy.
    
    Configures:
    - Disable Play Protect app verification
    - Allow installing apps from unknown sources (for our APK)
    - Security restrictions (no factory reset, etc.)
    """
    service = _get_service()
    
    policy = {
        # CRITICAL: Disable Play Protect and allow untrusted apps
        "advancedSecurityOverrides": {
            "untrustedAppsPolicy": "ALLOW_INSTALL_DEVICE_WIDE",
            "googlePlayProtectVerifyApps": "VERIFY_APPS_USER_CHOICE",
            "developerSettings": "DEVELOPER_SETTINGS_ALLOWED",
        },
        # Device-level security policies
        "factoryResetDisabled": True,
        "safeBootDisabled": True,
        "screenCaptureDisabled": True,
        "addUserDisabled": True,
        "removeUserDisabled": True,
        "modifyAccountsDisabled": False,
        # System update policy
        "systemUpdate": {
            "type": "WINDOWED",
            "startMinutes": 120,  # 2:00 AM
            "endMinutes": 300     # 5:00 AM
        },
        "skipFirstUseHintsEnabled": True,
        "adjustVolumeDisabled": False,
        "funDisabled": True,
        "networkEscapeHatchEnabled": True,
        # Allow all apps (don't restrict to Play Store only)
        "playStoreMode": "BLACKLIST",
    }

    result = service.enterprises().policies().patch(
        name=f"{enterprise_name}/policies/{policy_name}",
        body=policy
    ).execute()

    logger.info(f"Policy created/updated: {result.get('name')}")
    return result


def create_enrollment_token(enterprise_name: str, policy_name: str = "emi-locker-policy") -> dict:
    """Generate an enrollment token for QR code provisioning.
    
    The token is embedded in the QR code. When a device scans it:
    1. Android downloads Google's Cloud DPC from Play Store (trusted, no Play Protect block)
    2. Cloud DPC activates as Device Owner
    3. Cloud DPC applies the policy (force-installs EMI Locker)
    4. EMI Locker starts and runs its ZTE enrollment pipeline
    """
    service = _get_service()

    token_body = {
        "policyName": f"{enterprise_name}/policies/{policy_name}",
        "duration": "86400s",  # 24 hours
        "allowPersonalUsage": "PERSONAL_USAGE_DISALLOWED",
        "oneTimeOnly": False,  # Reusable token for bulk enrollment
    }

    token = service.enterprises().enrollmentTokens().create(
        parent=enterprise_name,
        body=token_body
    ).execute()

    logger.info(f"Enrollment token created: {token.get('name')}")
    # Google returns a ready-to-use 'qrCode' field - use it!
    if token.get('qrCode'):
        logger.info("Using Google-provided QR code from enrollment token")
    return token


def generate_qr_payload(enrollment_token_value: str) -> dict:
    """Generate the QR code provisioning payload using Google's Cloud DPC.
    Fallback only - prefer using Google's qrCode from the enrollment token.
    """
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": CLOUD_DPC_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": CLOUD_DPC_SIGNATURE,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": CLOUD_DPC_DOWNLOAD,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
            "com.google.android.apps.work.clouddpc.EXTRA_ENROLLMENT_TOKEN": enrollment_token_value
        },
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    }
    return payload


def generate_qr_string(enrollment_token_value: str) -> str:
    """Generate compact QR string for provisioning.
    Fallback only - prefer using Google's qrCode from the enrollment token.
    """
    payload = generate_qr_payload(enrollment_token_value)
    return json.dumps(payload, separators=(",", ":"))


def list_devices(enterprise_name: str) -> list:
    """List all enrolled devices in the enterprise."""
    service = _get_service()
    result = service.enterprises().devices().list(
        parent=enterprise_name
    ).execute()
    return result.get("devices", [])


def delete_device(device_name: str) -> bool:
    """Delete a single device from the enterprise to free up quota."""
    service = _get_service()
    try:
        service.enterprises().devices().delete(name=device_name).execute()
        logger.info(f"Device deleted: {device_name}")
        return True
    except Exception as e:
        logger.error(f"Failed to delete device {device_name}: {e}")
        return False


def cleanup_all_devices(enterprise_name: str) -> dict:
    """Delete ALL enrolled devices from the enterprise to free up quota.
    
    This is the fix for 'organization reached its usage limit' error.
    Google's AMAPI has per-enterprise device limits. Deleting unused devices
    frees up slots for new enrollments.
    """
    devices = list_devices(enterprise_name)
    total = len(devices)
    deleted = 0
    failed = 0
    
    for device in devices:
        device_name = device.get("name", "")
        if device_name:
            if delete_device(device_name):
                deleted += 1
            else:
                failed += 1
    
    logger.info(f"Cleanup complete: {deleted}/{total} devices deleted, {failed} failed")
    return {"total": total, "deleted": deleted, "failed": failed}


def create_new_enterprise() -> dict:
    """Create a brand new enterprise to bypass device limits.
    
    When an enterprise hits Google's device enrollment limit and cleanup
    isn't sufficient, creating a new enterprise resets the counter.
    Returns the new enterprise name that should replace AMAPI_ENTERPRISE_NAME.
    """
    service = _get_service()
    project_id = settings.AMAPI_PROJECT_ID

    enterprise = service.enterprises().create(
        projectId=project_id,
        agreementAccepted=True,
        body={
            "enterpriseDisplayName": "RR Locker EMI Finance",
        }
    ).execute()

    new_name = enterprise.get("name", "")
    logger.info(f"New enterprise created: {new_name}")
    
    # Update settings in-memory so subsequent calls use the new enterprise
    settings.AMAPI_ENTERPRISE_NAME = new_name
    
    # Create policy on the new enterprise
    try:
        create_policy(new_name)
        logger.info(f"Policy created on new enterprise: {new_name}")
    except Exception as e:
        logger.warning(f"Policy creation on new enterprise failed: {e}")
    
    return {
        "enterprise_name": new_name,
        "enterprise": enterprise
    }


def lock_device(enterprise_name: str, device_name: str) -> dict:
    """Lock a device via AMAPI command."""
    service = _get_service()
    return service.enterprises().devices().issueCommand(
        name=device_name,
        body={"type": "LOCK"}
    ).execute()


def reset_device(enterprise_name: str, device_name: str) -> dict:
    """Factory reset a device via AMAPI command."""
    service = _get_service()
    return service.enterprises().devices().issueCommand(
        name=device_name,
        body={"type": "RESET_PASSWORD"}
    ).execute()


def wipe_device(device_name: str) -> dict:
    """Wipe (factory reset) a device via AMAPI."""
    service = _get_service()
    return service.enterprises().devices().delete(
        name=device_name,
        wipeDataFlags=["WIPE_EXTERNAL_STORAGE"]
    ).execute()
