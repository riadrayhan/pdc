from pydantic import BaseModel
from typing import Optional
from datetime import datetime


class DeviceFingerprintBase(BaseModel):
    """Device fingerprint for identification after factory reset"""
    imei: Optional[str] = None
    imei2: Optional[str] = None
    serial_number: Optional[str] = None
    persistent_device_id: Optional[str] = None
    android_id: Optional[str] = None
    
    # Device info
    manufacturer: Optional[str] = None
    brand: Optional[str] = None
    model: Optional[str] = None
    device: Optional[str] = None
    product: Optional[str] = None
    board: Optional[str] = None
    hardware: Optional[str] = None
    
    # Software info
    android_version: Optional[str] = None
    sdk_version: Optional[int] = None
    build_id: Optional[str] = None
    build_fingerprint: Optional[str] = None
    
    # SIM info
    sim_operator: Optional[str] = None
    sim_operator_name: Optional[str] = None
    network_operator: Optional[str] = None
    phone_type: Optional[int] = None


class DeviceEnrollRequestV2(DeviceFingerprintBase):
    """Enhanced enrollment request with full fingerprint"""
    fcm_token: Optional[str] = None
    app_version: Optional[str] = None
    is_device_owner: bool = False
    is_admin_active: bool = False


class DeviceStatusCheck(BaseModel):
    """Request to check device status by fingerprint"""
    imei: Optional[str] = None
    imei2: Optional[str] = None
    serial_number: Optional[str] = None
    persistent_device_id: Optional[str] = None
    android_id: Optional[str] = None


class DeviceStatusResponse(BaseModel):
    """Response for device status check"""
    known_device: bool = False
    needs_re_enrollment: bool = False
    should_lock: bool = False
    apk_url: Optional[str] = None
    lock_message: Optional[str] = None
    device_id: Optional[int] = None
    customer_name: Optional[str] = None


class EnrollResponse(BaseModel):
    """Response after device enrollment"""
    success: bool
    device_token: Optional[str] = None
    device_id: Optional[str] = None
    message: str = ""
