"""
Device Fingerprint Service
Handles device identification and re-enrollment after factory reset
"""
from datetime import datetime
from typing import Optional, Tuple
from fastapi import HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import select, or_

from app.models.device import Device, DeviceStatus
from app.models.emi import EMIContract
from app.schemas.fingerprint import (
    DeviceStatusCheck, 
    DeviceStatusResponse, 
    DeviceEnrollRequestV2,
    EnrollResponse
)
from app.core.config import settings


class DeviceFingerprintService:
    """Service to match devices by fingerprint after factory reset"""
    
    def __init__(self, db: Session):
        self.db = db
    
    async def check_device_status(self, request: DeviceStatusCheck) -> DeviceStatusResponse:
        """
        Check if device is known by matching fingerprint
        Returns status including whether device should be locked
        """
        device = await self.find_device_by_fingerprint(request)
        
        if not device:
            return DeviceStatusResponse(
                known_device=False,
                needs_re_enrollment=False,
                should_lock=False
            )
        
        # Device found - check if it was factory reset
        needs_re_enrollment = await self.check_needs_re_enrollment(device, request)
        
        # Check if device should be locked (overdue payment)
        should_lock, lock_message = await self.check_should_lock(device)
        
        # Get customer name if available
        customer_name = None
        if device.customer:
            customer_name = device.customer.name
        
        return DeviceStatusResponse(
            known_device=True,
            needs_re_enrollment=needs_re_enrollment,
            should_lock=should_lock,
            lock_message=lock_message,
            device_id=str(device.id),
            customer_name=customer_name,
            apk_url=settings.APK_DOWNLOAD_URL if needs_re_enrollment else None
        )
    
    async def find_device_by_fingerprint(self, request: DeviceStatusCheck) -> Optional[Device]:
        """
        Find device by matching fingerprint data
        Priority: IMEI > Persistent ID > Serial Number
        """
        # Try IMEI first (most reliable)
        if request.imei:
            result = self.db.execute(
                select(Device).where(Device.imei == request.imei)
            )
            device = result.scalar_one_or_none()
            if device:
                return device
        
        # Try second IMEI
        if request.imei2:
            result = self.db.execute(
                select(Device).where(Device.imei2 == request.imei2)
            )
            device = result.scalar_one_or_none()
            if device:
                return device
        
        # Try persistent device ID
        if request.persistent_device_id:
            result = self.db.execute(
                select(Device).where(Device.persistent_device_id == request.persistent_device_id)
            )
            device = result.scalar_one_or_none()
            if device:
                return device
        
        # Try serial number
        if request.serial_number:
            result = self.db.execute(
                select(Device).where(Device.serial_number == request.serial_number)
            )
            device = result.scalar_one_or_none()
            if device:
                return device
        
        return None
    
    async def check_needs_re_enrollment(self, device: Device, request: DeviceStatusCheck) -> bool:
        """
        Check if device needs re-enrollment (factory reset detected)
        """
        # If Android ID changed, device was likely reset
        if request.android_id and device.android_id:
            if request.android_id != device.android_id:
                return True
        
        # If device status is WIPED
        if device.status == DeviceStatus.WIPED:
            return True
        
        return False
    
    async def check_should_lock(self, device: Device) -> Tuple[bool, Optional[str]]:
        """
        Check if device should be locked based on payment status
        """
        # Already locked
        if device.status == DeviceStatus.LOCKED:
            return True, "Device locked due to overdue payment. Contact EMI provider."
        
        # Check for overdue payments
        result = self.db.execute(
            select(EMIContract).where(
                EMIContract.device_id == device.id,
                EMIContract.status == "active"
            )
        )
        contract = result.scalar_one_or_none()
        
        if contract and contract.has_overdue_payment:
            return True, f"EMI payment overdue. Amount due: ₹{contract.overdue_amount}"
        
        return False, None
    
    async def enroll_device_v2(self, request: DeviceEnrollRequestV2) -> EnrollResponse:
        """
        Enroll device with full fingerprint or re-enroll after reset
        """
        # Check if device exists
        existing = await self.find_device_by_fingerprint(
            DeviceStatusCheck(
                imei=request.imei,
                imei2=request.imei2,
                serial_number=request.serial_number,
                persistent_device_id=request.persistent_device_id
            )
        )
        
        if existing:
            # Re-enrollment - update device info
            return await self.re_enroll_device(existing, request)
        else:
            # New enrollment
            return await self.new_enrollment(request)
    
    async def re_enroll_device(self, device: Device, request: DeviceEnrollRequestV2) -> EnrollResponse:
        """
        Re-enroll device after factory reset
        """
        # SECURITY: Refuse re-enrollment if admin has blacklisted/deactivated the device.
        # This is the server-side "IMEI blacklist" that complements on-device FRP:
        # even if a customer bypasses FRP and reinstalls the app, the server rejects
        # enrollment so the device cannot rejoin the fleet under a new owner.
        if device.status == DeviceStatus.DEACTIVATED:
            raise HTTPException(
                status_code=403,
                detail="DEVICE_BLACKLISTED: This device has been deactivated by the EMI provider. Contact support."
            )

        # Increment reset counter
        device.factory_reset_count = (device.factory_reset_count or 0) + 1
        device.last_factory_reset = datetime.utcnow()
        
        # Update device info
        device.android_id = request.android_id
        device.fcm_token = request.fcm_token
        device.app_version = request.app_version
        device.is_device_owner = request.is_device_owner
        device.is_admin_active = request.is_admin_active
        device.build_fingerprint = request.build_fingerprint
        device.is_online = True
        device.last_seen = datetime.utcnow()
        
        # Update status
        if device.status == DeviceStatus.WIPED:
            device.status = DeviceStatus.ACTIVE
        
        self.db.commit()
        
        return EnrollResponse(
            success=True,
            device_id=str(device.id),
            device_token=self.generate_device_token(device),
            message="Device re-enrolled successfully after factory reset"
        )
    
    async def new_enrollment(self, request: DeviceEnrollRequestV2) -> EnrollResponse:
        """
        New device enrollment
        """
        device = Device(
            imei=request.imei,
            imei2=request.imei2,
            serial_number=request.serial_number,
            persistent_device_id=request.persistent_device_id,
            android_id=request.android_id,
            device_model=request.model,
            manufacturer=request.manufacturer,
            brand=request.brand,
            device_name=request.device,
            product=request.product,
            board=request.board,
            hardware=request.hardware,
            android_version=request.android_version,
            sdk_version=str(request.sdk_version) if request.sdk_version else None,
            build_fingerprint=request.build_fingerprint,
            fcm_token=request.fcm_token,
            app_version=request.app_version,
            is_device_owner=request.is_device_owner,
            is_admin_active=request.is_admin_active,
            status=DeviceStatus.ACTIVE,
            is_online=True,
            last_seen=datetime.utcnow(),
            enrolled_at=datetime.utcnow()
        )
        
        self.db.add(device)
        self.db.commit()
        self.db.refresh(device)
        
        return EnrollResponse(
            success=True,
            device_id=str(device.id),
            device_token=self.generate_device_token(device),
            message="Device enrolled successfully"
        )
    
    def generate_device_token(self, device: Device) -> str:
        """Generate authentication token for device"""
        import secrets
        return secrets.token_urlsafe(32)
