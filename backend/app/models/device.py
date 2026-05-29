import uuid
from datetime import datetime
from sqlalchemy import Column, String, DateTime, Boolean, Text, Enum as SQLEnum, ForeignKey, Integer
from sqlalchemy.orm import relationship
import enum

from app.core.database import Base
from app.core.types import UUID


class DeviceStatus(str, enum.Enum):
    PENDING = "pending"          # Waiting for enrollment
    ACTIVE = "active"            # Normal operation
    LOCKED = "locked"            # Device is locked
    WARNING = "warning"          # Warning issued
    WIPED = "wiped"              # Factory reset done
    DEACTIVATED = "deactivated"  # Removed from system


class Device(Base):
    __tablename__ = "devices"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    # IMEI can be null on Android 10+ without special permissions
    imei = Column(String(20), unique=True, nullable=True, index=True)
    imei2 = Column(String(20), index=True)
    serial_number = Column(String(100), index=True)
    
    # Persistent ID - survives factory reset (hash of hardware IDs)
    # This is the primary device identifier when IMEI is not available
    persistent_device_id = Column(String(64), unique=True, index=True)
    
    # Android ID - changes after factory reset
    android_id = Column(String(64))
    
    device_model = Column(String(100))
    manufacturer = Column(String(100))
    brand = Column(String(100))
    device_name = Column(String(100))
    product = Column(String(100))
    board = Column(String(100))
    hardware = Column(String(100))
    build_fingerprint = Column(String(255))
    
    android_version = Column(String(20))
    sdk_version = Column(String(10))
    
    # FCM Token for push notifications
    fcm_token = Column(Text)
    
    # Status
    status = Column(SQLEnum(DeviceStatus), default=DeviceStatus.PENDING, index=True)
    is_online = Column(Boolean, default=False)
    last_seen = Column(DateTime)
    
    # Track factory resets
    factory_reset_count = Column(Integer, default=0)
    last_factory_reset = Column(DateTime)
    
    # Security
    is_rooted = Column(Boolean, default=False)
    safety_net_passed = Column(Boolean)
    is_device_owner = Column(Boolean, default=False)
    is_admin_active = Column(Boolean, default=False)
    app_version = Column(String(20))
    
    # Enrollment
    enrollment_code = Column(String(50), unique=True)
    enrolled_at = Column(DateTime)
    enrolled_by = Column(UUID(), ForeignKey("users.id"))
    
    # GPS Tracking
    last_latitude = Column(String(50))
    last_longitude = Column(String(50))
    last_location_time = Column(DateTime)
    last_location_address = Column(Text)
    
    # Camera
    camera_active = Column(Boolean, default=False)
    last_photo_url = Column(Text)
    last_photo_time = Column(DateTime)
    
    # App visibility state
    is_app_hidden = Column(Boolean, default=False)
    is_app_disabled = Column(Boolean, default=False)
    
    # Real-time device info (updated every 2 seconds via heartbeat)
    battery_level = Column(Integer)
    is_charging = Column(Boolean)
    network_type = Column(String(20))
    
    # Relations
    customer_id = Column(UUID(), ForeignKey("customers.id"), index=True)
    customer = relationship("Customer", back_populates="devices")
    commands = relationship("DeviceCommand", back_populates="device")
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def __repr__(self):
        return f"<Device {self.imei}>"
