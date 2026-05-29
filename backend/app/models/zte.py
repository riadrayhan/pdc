"""
Zero Touch Enrollment (ZTE) Models

Tracks ZTE configuration (persisted in DB), enrollment events, 
and provisioning history for comprehensive ZTE management.
"""

import uuid
import enum
from datetime import datetime, timedelta
from sqlalchemy import Column, String, DateTime, Boolean, Text, Integer, Float, JSON
from sqlalchemy import Enum as SQLEnum
from app.core.database import Base
from app.core.types import UUID


class ZTEEnrollmentStatus(str, enum.Enum):
    """Status of a ZTE enrollment attempt"""
    PROVISIONED = "provisioned"        # QR scanned, Device Owner set
    INITIALIZING = "initializing"      # ZTE service started
    GRANTING_PERMISSIONS = "granting_permissions"
    CONFIGURING_WIFI = "configuring_wifi"
    GETTING_FCM_TOKEN = "getting_fcm_token"
    COLLECTING_FINGERPRINT = "collecting_fingerprint"
    CHECKING_SERVER = "checking_server"
    ENROLLING = "enrolling"
    VERIFYING = "verifying"
    APPLYING_PROTECTIONS = "applying_protections"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


class ZTEConfig(Base):
    """
    Persisted ZTE configuration — replaces in-memory config.
    Only one row exists (singleton pattern via config_key).
    """
    __tablename__ = "zte_config"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    config_key = Column(String(50), unique=True, default="default", nullable=False)
    
    # WiFi settings
    wifi_ssid = Column(String(64), default="")
    wifi_password = Column(String(128), default="")
    wifi_security = Column(String(10), default="WPA")  # NONE, WEP, WPA
    wifi_hidden = Column(Boolean, default=False)
    
    # Default device settings
    default_lock_message = Column(Text, default="আপনার EMI বকেয়া আছে। অনুগ্রহ করে পরিশোধ করুন।")
    default_contact_number = Column(String(20), default="")
    
    # Enrollment behavior
    auto_enroll = Column(Boolean, default=True)
    auto_lock_on_enroll = Column(Boolean, default=False)
    skip_encryption = Column(Boolean, default=True)
    leave_all_system_apps = Column(Boolean, default=True)
    
    # Device settings
    locale = Column(String(10), default="bn_BD")
    time_zone = Column(String(50), default="Asia/Dhaka")
    custom_apk_url = Column(String(500), default="")
    
    # Status
    enabled = Column(Boolean, default=True)
    
    # Metadata
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def to_dict(self):
        return {
            "wifi_ssid": self.wifi_ssid or "",
            "wifi_password": self.wifi_password or "",
            "wifi_security": self.wifi_security or "WPA",
            "wifi_hidden": self.wifi_hidden or False,
            "default_lock_message": self.default_lock_message or "",
            "default_contact_number": self.default_contact_number or "",
            "auto_enroll": self.auto_enroll if self.auto_enroll is not None else True,
            "auto_lock_on_enroll": self.auto_lock_on_enroll or False,
            "skip_encryption": self.skip_encryption if self.skip_encryption is not None else True,
            "leave_all_system_apps": self.leave_all_system_apps if self.leave_all_system_apps is not None else True,
            "locale": self.locale or "bn_BD",
            "time_zone": self.time_zone or "Asia/Dhaka",
            "custom_apk_url": self.custom_apk_url or "",
            "enabled": self.enabled if self.enabled is not None else True,
        }

    def __repr__(self):
        return f"<ZTEConfig key={self.config_key}>"


class ZTEEnrollmentEvent(Base):
    """
    Tracks each ZTE enrollment attempt with full lifecycle.
    Created when a device starts ZTE provisioning, updated as it progresses.
    """
    __tablename__ = "zte_enrollment_events"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    
    # Device identification (before enrollment assigns device_id)
    imei = Column(String(20), index=True)
    imei2 = Column(String(20))
    serial_number = Column(String(100))
    persistent_device_id = Column(String(64), index=True)
    android_id = Column(String(64))
    
    # Device info
    manufacturer = Column(String(100))
    model = Column(String(100))
    android_version = Column(String(20))
    
    # Enrollment progress
    status = Column(SQLEnum(ZTEEnrollmentStatus), default=ZTEEnrollmentStatus.PROVISIONED, index=True)
    current_phase = Column(Integer, default=0)
    total_phases = Column(Integer, default=11)
    progress_percent = Column(Integer, default=0)
    
    # Timing
    started_at = Column(DateTime, default=datetime.utcnow)
    completed_at = Column(DateTime, nullable=True)
    elapsed_seconds = Column(Float, nullable=True)
    
    # Result
    device_id = Column(String(100), nullable=True)  # Assigned after successful enrollment
    fcm_token = Column(Text, nullable=True)
    enrollment_method = Column(String(20), default="zte")  # zte, manual, re-enroll
    
    # Error tracking
    retry_count = Column(Integer, default=0)
    last_error = Column(Text, nullable=True)
    failure_phase = Column(Integer, nullable=True)
    
    # Network info
    network_type = Column(String(20), nullable=True)  # wifi, cellular
    wifi_ssid = Column(String(64), nullable=True)
    
    # SIM info
    sim_operator = Column(String(100), nullable=True)
    sim_country = Column(String(10), nullable=True)
    phone_number = Column(String(20), nullable=True)
    
    # Server info
    server_url = Column(String(500), nullable=True)
    zte_version = Column(String(10), default="2.0")
    
    # Extra data (JSON for extensibility)
    extra_data = Column(JSON, nullable=True)
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def __repr__(self):
        return f"<ZTEEvent {self.id} status={self.status} imei={self.imei}>"


class ZTEProvisioningLog(Base):
    """
    Detailed step-by-step log for each ZTE enrollment.
    Each phase transition creates a new log entry.
    """
    __tablename__ = "zte_provisioning_logs"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    event_id = Column(UUID(), nullable=False, index=True)  # FK to ZTEEnrollmentEvent
    
    phase = Column(Integer, nullable=False)
    phase_name = Column(String(50), nullable=False)
    status = Column(String(20), nullable=False)  # started, completed, failed, skipped
    message = Column(Text, nullable=True)
    duration_ms = Column(Integer, nullable=True)
    
    timestamp = Column(DateTime, default=datetime.utcnow)
    
    def __repr__(self):
        return f"<ZTELog phase={self.phase_name} status={self.status}>"
