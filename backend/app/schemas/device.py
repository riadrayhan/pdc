from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from uuid import UUID
from app.models.device import DeviceStatus


class DeviceBase(BaseModel):
    imei: Optional[str] = Field(None, min_length=15, max_length=17)
    imei2: Optional[str] = None
    serial_number: Optional[str] = None
    device_model: Optional[str] = None
    manufacturer: Optional[str] = None


class DeviceEnroll(DeviceBase):
    android_version: Optional[str] = None
    sdk_version: Optional[str] = None
    fcm_token: Optional[str] = None


class DeviceUpdate(BaseModel):
    fcm_token: Optional[str] = None
    android_version: Optional[str] = None
    app_version: Optional[str] = None
    is_rooted: Optional[bool] = None
    safety_net_passed: Optional[bool] = None


class DeviceStatusUpdate(BaseModel):
    status: DeviceStatus
    reason: Optional[str] = None


class DeviceResponse(DeviceBase):
    id: UUID
    status: DeviceStatus
    is_online: bool
    last_seen: Optional[datetime] = None
    fcm_token: Optional[str] = None
    android_version: Optional[str] = None
    sdk_version: Optional[str] = None
    app_version: Optional[str] = None
    brand: Optional[str] = None
    device_name: Optional[str] = None
    product: Optional[str] = None
    board: Optional[str] = None
    hardware: Optional[str] = None
    build_fingerprint: Optional[str] = None
    android_id: Optional[str] = None
    persistent_device_id: Optional[str] = None
    is_device_owner: Optional[bool] = None
    is_admin_active: Optional[bool] = None
    factory_reset_count: Optional[int] = 0
    enrolled_at: Optional[datetime] = None
    customer_id: Optional[UUID] = None
    last_latitude: Optional[str] = None
    last_longitude: Optional[str] = None
    last_location_time: Optional[datetime] = None
    last_location_address: Optional[str] = None
    camera_active: Optional[bool] = False
    last_photo_url: Optional[str] = None
    last_photo_time: Optional[datetime] = None
    is_app_hidden: Optional[bool] = False
    is_app_disabled: Optional[bool] = False
    battery_level: Optional[int] = None
    is_charging: Optional[bool] = None
    network_type: Optional[str] = None
    created_at: datetime

    class Config:
        from_attributes = True


class DeviceListResponse(BaseModel):
    total: int
    devices: List[DeviceResponse]


class DeviceHeartbeat(BaseModel):
    imei: Optional[str] = None
    imei2: Optional[str] = None
    fcm_token: Optional[str] = None
    battery_level: Optional[int] = None
    is_charging: Optional[bool] = None
    network_type: Optional[str] = None
    app_version: Optional[str] = None
    device_name: Optional[str] = None
    brand: Optional[str] = None
    manufacturer: Optional[str] = None
    device_model: Optional[str] = None
    serial_number: Optional[str] = None
    android_version: Optional[str] = None
    is_device_owner: Optional[bool] = None
    is_admin_active: Optional[bool] = None


class EnrollmentQRData(BaseModel):
    enrollment_code: str
    server_url: str
    created_at: datetime
    expires_at: datetime
