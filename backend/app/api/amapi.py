"""
AMAPI (Android Management API) Endpoints

Provides QR-code based provisioning that uses Google's trusted Cloud DPC
instead of directly downloading our APK. This completely bypasses Play Protect.

Setup flow:
1. POST /amapi/setup - Create enterprise (one-time)
2. POST /amapi/policy - Create/update device policy  
3. POST /amapi/enrollment-token - Generate enrollment token
4. GET  /amapi/qr - Get QR code data for device provisioning
"""

import json
import logging
from typing import Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from app.services import amapi_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/amapi", tags=["Android Management API"])


# =============== Schemas ===============

class SetupResponse(BaseModel):
    configured: bool
    enterprise_name: Optional[str] = None
    message: str


class EnrollmentTokenResponse(BaseModel):
    token_value: str
    token_name: str
    qr_string: str
    expiry: Optional[str] = None


class PolicyResponse(BaseModel):
    policy_name: str
    message: str


# =============== Endpoints ===============

@router.get("/status")
async def amapi_status():
    """Check if AMAPI is configured and ready."""
    configured = amapi_service.is_configured()
    enterprise = amapi_service.get_enterprise_name()
    return {
        "configured": configured,
        "has_enterprise": bool(enterprise),
        "enterprise_name": enterprise,
        "message": "AMAPI ready" if (configured and enterprise) else
                   "AMAPI configured but no enterprise" if configured else
                   "AMAPI not configured - set AMAPI_SERVICE_ACCOUNT_JSON and AMAPI_PROJECT_ID"
    }


@router.post("/setup", response_model=SetupResponse)
async def setup_enterprise():
    """Create a new Android Enterprise (one-time setup).
    
    After calling this, save the enterprise_name as AMAPI_ENTERPRISE_NAME env var.
    """
    if not amapi_service.is_configured():
        raise HTTPException(
            status_code=400,
            detail="AMAPI not configured. Set AMAPI_SERVICE_ACCOUNT_JSON and AMAPI_PROJECT_ID environment variables."
        )

    try:
        result = amapi_service.create_enterprise()
        return SetupResponse(
            configured=True,
            enterprise_name=result["enterprise_name"],
            message=f"Enterprise created! Save this as AMAPI_ENTERPRISE_NAME: {result['enterprise_name']}"
        )
    except Exception as e:
        logger.error(f"Enterprise creation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/policy", response_model=PolicyResponse)
async def create_or_update_policy():
    """Create or update the device management policy.
    
    This policy tells Google's DPC to force-install the EMI Locker app
    and apply security restrictions (no factory reset, no USB, etc.)
    """
    enterprise = amapi_service.get_enterprise_name()
    if not enterprise:
        raise HTTPException(status_code=400, detail="No enterprise configured. Run /amapi/setup first.")

    try:
        result = amapi_service.create_policy(enterprise)
        return PolicyResponse(
            policy_name=result.get("name", ""),
            message="Policy created/updated. EMI Locker will be force-installed on enrolled devices."
        )
    except Exception as e:
        logger.error(f"Policy creation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/enrollment-token", response_model=EnrollmentTokenResponse)
async def create_enrollment_token():
    """Generate a reusable enrollment token for QR provisioning.
    
    The QR code generated from this token uses Google's trusted Cloud DPC,
    which downloads from Play Store - completely bypassing Play Protect.
    """
    enterprise = amapi_service.get_enterprise_name()
    if not enterprise:
        raise HTTPException(status_code=400, detail="No enterprise configured. Run /amapi/setup first.")

    try:
        token = amapi_service.create_enrollment_token(enterprise)
        token_value = token.get("value", "")
        # Prefer Google's pre-built qrCode
        google_qr = token.get("qrCode", "")
        qr_string = google_qr if google_qr else amapi_service.generate_qr_string(token_value)
        return EnrollmentTokenResponse(
            token_value=token_value,
            token_name=token.get("name", ""),
            qr_string=qr_string,
            expiry=token.get("expirationTimestamp")
        )
    except Exception as e:
        logger.error(f"Enrollment token creation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/qr")
async def get_provisioning_qr():
    """DEPRECATED: AMAPI QR provisioning is blocked by Google for EMI use cases.
    
    Google's Permissible Usage Policy explicitly bans EMI/device financing.
    Enterprise quota = 0 (denied). This endpoint will ALWAYS fail.
    
    Use instead:
    - /zte/provisioning-qr or /zte/provisioning-data (Custom DPC QR - no Google limits)
    - USB/ADB setup (most reliable)
    """
    raise HTTPException(
        status_code=410,
        detail={
            "error": "AMAPI QR is permanently disabled for EMI use cases",
            "reason": "Google's Permissible Usage Policy bans EMI/device financing from using Android Management API. Enterprise quota = 0.",
            "solution": "Use Custom DPC QR from dashboard (Device Setup → QR Code tab) or USB/ADB method. These have NO Google limits.",
            "custom_dpc_qr": "/api/v1/zte/provisioning-qr",
            "custom_dpc_data": "/api/v1/zte/provisioning-data",
        }
    )


@router.get("/devices")
async def list_enrolled_devices():
    """List all devices enrolled via AMAPI."""
    enterprise = amapi_service.get_enterprise_name()
    if not enterprise:
        raise HTTPException(status_code=400, detail="No enterprise configured.")

    try:
        devices = amapi_service.list_devices(enterprise)
        return {"devices": devices, "count": len(devices)}
    except Exception as e:
        logger.error(f"Device listing failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/devices/{device_id:path}")
async def delete_device(device_id: str):
    """Delete a specific AMAPI-enrolled device to free up quota."""
    try:
        success = amapi_service.delete_device(device_id)
        if success:
            return {"status": "deleted", "device": device_id}
        raise HTTPException(status_code=500, detail=f"Failed to delete device: {device_id}")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Device deletion failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/cleanup")
async def cleanup_devices():
    """Delete ALL AMAPI-enrolled devices to free up quota.
    
    Fix for: 'organization reached its usage limit' error.
    Google limits the number of devices per enterprise.
    This deletes all old enrolled devices to free up slots.
    """
    enterprise = amapi_service.get_enterprise_name()
    if not enterprise:
        raise HTTPException(status_code=400, detail="No enterprise configured.")

    try:
        result = amapi_service.cleanup_all_devices(enterprise)
        return {
            "status": "cleanup_complete",
            "total_devices": result["total"],
            "deleted": result["deleted"],
            "failed": result["failed"],
            "message": f"Deleted {result['deleted']}/{result['total']} devices. You can now enroll new devices."
        }
    except Exception as e:
        logger.error(f"Cleanup failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/new-enterprise")
async def create_new_enterprise():
    """Create a brand new enterprise to bypass device limits.
    
    Nuclear option when cleanup isn't enough. Creates a fresh enterprise
    with zero device count, resets everything.
    """
    if not amapi_service.is_configured():
        raise HTTPException(status_code=400, detail="AMAPI not configured.")

    try:
        result = amapi_service.create_new_enterprise()
        return {
            "status": "new_enterprise_created",
            "enterprise_name": result["enterprise_name"],
            "message": f"New enterprise created: {result['enterprise_name']}. "
                       f"Update AMAPI_ENTERPRISE_NAME env var to persist across restarts."
        }
    except Exception as e:
        logger.error(f"New enterprise creation failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/callback")
async def enterprise_callback(enterpriseToken: str = ""):
    """Callback URL for enterprise signup flow.
    Used internally by Google during enterprise creation.
    """
    return {"status": "ok", "token": enterpriseToken}
