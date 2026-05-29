from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from typing import Optional
from uuid import UUID
from datetime import datetime

from app.core import get_db, get_current_user, get_current_admin_user
from app.models import User, Device, DeviceStatus
from app.schemas import (
    DeviceEnroll, DeviceUpdate, DeviceStatusUpdate, DeviceResponse,
    DeviceListResponse, DeviceHeartbeat
)
from app.services import DeviceService

router = APIRouter(prefix="/devices", tags=["Devices"])


@router.post("/enroll", response_model=DeviceResponse, status_code=status.HTTP_201_CREATED)
async def enroll_device(
    device_data: DeviceEnroll,
    db: Session = Depends(get_db)
):
    """Enroll a new device (called from Android app)"""
    # Check if device already exists
    existing = DeviceService.get_device_by_imei(db, device_data.imei)
    if existing:
        # Update existing device
        existing.fcm_token = device_data.fcm_token
        existing.android_version = device_data.android_version
        existing.sdk_version = device_data.sdk_version
        existing.is_online = True
        existing.last_seen = __import__('datetime').datetime.utcnow()
        db.commit()
        db.refresh(existing)
        return existing
    
    device = DeviceService.enroll_device(db, device_data)
    return device


@router.get("", response_model=DeviceListResponse)
async def list_devices(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    status: Optional[DeviceStatus] = None,
    search: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """List all devices with filtering (NO AUTH REQUIRED)"""
    devices, total = DeviceService.list_devices(db, skip, limit, status, search)
    return DeviceListResponse(total=total, devices=devices)


@router.get("/{device_id}", response_model=DeviceResponse)
async def get_device(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Get device by ID (NO AUTH)"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    return device


@router.get("/imei/{imei}", response_model=DeviceResponse)
async def get_device_by_imei(
    imei: str,
    db: Session = Depends(get_db)
):
    """Get device by IMEI (NO AUTH)"""
    device = DeviceService.get_device_by_imei(db, imei)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    return device


@router.put("/{device_id}/status", response_model=DeviceResponse)
async def update_device_status(
    device_id: UUID,
    status_update: DeviceStatusUpdate,
    db: Session = Depends(get_db)
):
    """Update device status (lock/unlock) - NO AUTH"""
    device, command = DeviceService.update_device_status(
        db, device_id, status_update.status, None, status_update.reason
    )
    
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    return device


@router.post("/heartbeat")
async def device_heartbeat(
    heartbeat: DeviceHeartbeat,
    db: Session = Depends(get_db)
):
    """Device heartbeat endpoint (called every 2 seconds by Android app)
    Receives full device info: name, brand, IMEI, serial number, etc."""
    device = DeviceService.update_heartbeat(db, heartbeat)
    
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found. Please enroll first."
        )
    
    return {
        "status": device.status.value,
        "message": "Heartbeat received"
    }


@router.delete("/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_device(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Permanently delete a device and all its commands"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    # Delete all commands for this device first
    from app.models import DeviceCommand
    db.query(DeviceCommand).filter(DeviceCommand.device_id == device_id).delete()
    
    # Delete all contracts linked to this device
    from app.models import EMIContract, EMIPayment
    contracts = db.query(EMIContract).filter(EMIContract.device_id == device_id).all()
    for contract in contracts:
        db.query(EMIPayment).filter(EMIPayment.contract_id == contract.id).delete()
        db.delete(contract)
    
    db.delete(device)
    db.commit()
    return None


@router.post("/{device_id}/report-location")
async def report_device_location(
    device_id: UUID,
    location_data: dict,
    db: Session = Depends(get_db)
):
    """Report device location (called by Android app after GPS_TRACK command)"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    device.last_latitude = str(location_data.get("latitude", ""))
    device.last_longitude = str(location_data.get("longitude", ""))
    device.last_location_time = datetime.utcnow()
    device.last_location_address = location_data.get("address", "")
    db.commit()
    
    return {"status": "location_updated"}


@router.post("/{device_id}/report-photo")
async def report_device_photo(
    device_id: UUID,
    photo_data: dict,
    db: Session = Depends(get_db)
):
    """Report device camera photo (called by Android app after CAMERA_ON command)"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    device.last_photo_url = photo_data.get("photo_url", "")
    device.last_photo_time = datetime.utcnow()
    db.commit()
    
    return {"status": "photo_updated"}


@router.get("/{device_id}/location")
async def get_device_location(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Get the last known location of a device"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    return {
        "latitude": device.last_latitude,
        "longitude": device.last_longitude,
        "address": device.last_location_address,
        "updated_at": device.last_location_time.isoformat() if device.last_location_time else None,
    }


@router.get("/{device_id}/photo")
async def get_device_photo(
    device_id: UUID,
    db: Session = Depends(get_db)
):
    """Get the last captured photo from device camera"""
    device = DeviceService.get_device_by_id(db, device_id)
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found"
        )
    
    return {
        "photo_url": device.last_photo_url,
        "camera_active": device.camera_active,
        "updated_at": device.last_photo_time.isoformat() if device.last_photo_time else None,
    }
