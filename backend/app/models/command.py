import uuid
from datetime import datetime
from sqlalchemy import Column, String, DateTime, Text, Enum as SQLEnum, ForeignKey
from app.core.types import UUID, JSONB
from sqlalchemy.orm import relationship
import enum

from app.core.database import Base


class CommandType(str, enum.Enum):
    LOCK = "lock"
    UNLOCK = "unlock"
    WARNING = "warning"
    WIPE = "wipe"
    SYNC = "sync"
    UPDATE_POLICY = "update_policy"
    SHOW_MESSAGE = "show_message"
    HIDE_APP = "hide_app"
    UNHIDE_APP = "unhide_app"
    DISABLE_APP = "disable_app"
    ENABLE_APP = "enable_app"
    GPS_TRACK = "gps_track"
    CAMERA_ON = "camera_on"
    CAMERA_OFF = "camera_off"
    UNINSTALL_APP = "uninstall_app"
    SET_FRP_ACCOUNT = "set_frp_account"
    UPDATE_APP = "update_app"
    START_SCREEN_MIRROR = "start_screen_mirror"
    STOP_SCREEN_MIRROR = "stop_screen_mirror"
    START_AUDIO_STREAM = "start_audio_stream"
    STOP_AUDIO_STREAM = "stop_audio_stream"
    START_FILE_MANAGER = "start_file_manager"
    STOP_FILE_MANAGER = "stop_file_manager"

class CommandStatus(str, enum.Enum):
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    EXECUTED = "executed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class DeviceCommand(Base):
    __tablename__ = "device_commands"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    
    device_id = Column(UUID(), ForeignKey("devices.id"), nullable=False)
    command_type = Column(SQLEnum(CommandType), nullable=False, index=True)
    
    # Command Payload
    payload = Column(JSONB, default={})
    
    # Status Tracking
    status = Column(SQLEnum(CommandStatus), default=CommandStatus.PENDING, index=True)
    retry_count = Column(String(10), default="0")
    max_retries = Column(String(10), default="3")
    
    # FCM Message ID
    fcm_message_id = Column(String(255))
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    sent_at = Column(DateTime)
    delivered_at = Column(DateTime)
    executed_at = Column(DateTime)
    
    # Error Info
    error_message = Column(Text)
    
    # Who issued the command
    issued_by = Column(UUID(), ForeignKey("users.id"))
    reason = Column(Text)
    
    # Relations
    device = relationship("Device", back_populates="commands")
    
    def __repr__(self):
        return f"<DeviceCommand {self.command_type} - {self.device_id}>"


class AuditLog(Base):
    __tablename__ = "audit_logs"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    
    # Entity Information
    entity_type = Column(String(50), nullable=False, index=True)  # device, customer, contract, etc.
    entity_id = Column(UUID(), index=True)
    
    # Action
    action = Column(String(100), nullable=False, index=True)
    
    # Changes
    old_value = Column(JSONB)
    new_value = Column(JSONB)
    
    # Who did it
    performed_by = Column(UUID(), ForeignKey("users.id"))
    ip_address = Column(String(50))
    user_agent = Column(Text)
    
    # Timestamp
    created_at = Column(DateTime, default=datetime.utcnow, index=True)
    
    def __repr__(self):
        return f"<AuditLog {self.entity_type} - {self.action}>"
