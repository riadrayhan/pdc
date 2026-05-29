"""
Device Enrollment API Routes
Handles device registration and re-enrollment after factory reset
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_db
from app.schemas.fingerprint import (
    DeviceStatusCheck,
    DeviceStatusResponse,
    DeviceEnrollRequestV2,
    EnrollResponse
)
from app.services.fingerprint_service import DeviceFingerprintService

router = APIRouter(prefix="/enrollment", tags=["enrollment"])


@router.post("/check-status", response_model=DeviceStatusResponse)
async def check_device_status(
    request: DeviceStatusCheck,
    db: AsyncSession = Depends(get_db)
):
    """
    Check if device is known by the system.
    This is called when a device comes online to check if it was previously enrolled.
    
    The device sends its fingerprint (IMEI, Serial, Persistent ID) and server
    checks if it matches any known device.
    
    Returns:
    - known_device: True if device was previously enrolled
    - needs_re_enrollment: True if device was factory reset and needs re-enrollment
    - should_lock: True if device should be locked (overdue payment)
    - apk_url: URL to download app (if needs re-enrollment)
    """
    service = DeviceFingerprintService(db)
    return await service.check_device_status(request)


@router.post("/enroll", response_model=EnrollResponse)
async def enroll_device(
    request: DeviceEnrollRequestV2,
    db: AsyncSession = Depends(get_db)
):
    """
    Enroll a new device or re-enroll after factory reset.
    
    When a device is first set up with the app, it sends its full fingerprint
    to register with the system. If the device was previously enrolled (matched
    by IMEI/Serial), it will be re-enrolled with updated info.
    
    The fingerprint includes hardware identifiers that survive factory reset:
    - IMEI (primary and secondary)
    - Serial Number
    - Persistent Device ID (hash of hardware IDs)
    
    Returns a device token for future API calls.
    """
    service = DeviceFingerprintService(db)
    return await service.enroll_device_v2(request)


@router.get("/apk-url")
async def get_apk_download_url():
    """
    Get the APK download URL for device installation.
    Called when server instructs device to install/reinstall the app.
    """
    from app.core.config import settings
    return {
        "url": settings.APK_DOWNLOAD_URL,
        "version": settings.APP_VERSION,
        "checksum": settings.APK_CHECKSUM
    }
