"""Device metadata collection API.

The Android app POSTs collected data to `/metadata/collect` in batches keyed by
`type` (call_logs, sms, location, ...). The dashboard reads through GET
endpoints filtered by device_id.
"""
from typing import Optional, Dict, Any
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from sqlalchemy import func, desc

from app.core import get_db
from app.models.metadata import (
    METADATA_MODEL_MAP, COLUMN_RENAME,
    CallLogEntry, SmsEntry, LocationEntry, SimHistoryEntry,
    MobileMoneyEntry, TelecomUsageEntry, RideHailingEntry,
    MetaDeviceInfo, LocationDwellEntry, BehaviorScoreEntry,
    InstalledAppEntry, ContactEntry,
)
from app.schemas.metadata import MetadataBatch, MetadataAck

router = APIRouter(prefix="/metadata", tags=["Metadata"])


def _coerce_row(model_cls, row: Dict[str, Any], device_id: str) -> Dict[str, Any]:
    """Drop unknown keys, apply renames, attach device_id, drop SQLite id/synced."""
    valid_cols = {c.name for c in model_cls.__table__.columns}
    out: Dict[str, Any] = {"device_id": device_id}
    for k, v in row.items():
        if k in ("id", "synced"):
            continue
        target = COLUMN_RENAME.get(k, k)
        if target in valid_cols and target not in ("id", "created_at"):
            out[target] = v
    return out


@router.post("/collect", response_model=MetadataAck)
async def collect_metadata(batch: MetadataBatch, db: Session = Depends(get_db)):
    model_cls = METADATA_MODEL_MAP.get(batch.type)
    if not model_cls:
        raise HTTPException(status_code=400, detail=f"Unknown metadata type: {batch.type}")

    stored = 0
    for row in batch.data:
        try:
            payload = _coerce_row(model_cls, row, batch.device_id)
            db.add(model_cls(**payload))
            stored += 1
        except Exception:
            db.rollback()
            continue
    db.commit()
    return MetadataAck(status="ok", received=len(batch.data), stored=stored)


def _paginated(db, model_cls, device_id: Optional[str], limit: int, offset: int):
    q = db.query(model_cls)
    if device_id:
        q = q.filter(model_cls.device_id == device_id)
    total = q.count()
    items = q.order_by(desc(model_cls.created_at)).offset(offset).limit(limit).all()
    return {"total": total, "items": [_row_to_dict(i) for i in items]}


def _row_to_dict(row) -> Dict[str, Any]:
    d = {}
    for c in row.__table__.columns:
        v = getattr(row, c.name)
        d[c.name] = str(v) if c.name in ("id",) else v
    if "created_at" in d and d["created_at"] is not None:
        d["created_at"] = d["created_at"].isoformat()
    return d


@router.get("/call_logs")
async def list_call_logs(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                         offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, CallLogEntry, device_id, limit, offset)


@router.get("/sms")
async def list_sms(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                   offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, SmsEntry, device_id, limit, offset)


@router.get("/location")
async def list_location(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                        offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, LocationEntry, device_id, limit, offset)


@router.get("/sim_history")
async def list_sim_history(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                           offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, SimHistoryEntry, device_id, limit, offset)


@router.get("/mobile_money")
async def list_mobile_money(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                            offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, MobileMoneyEntry, device_id, limit, offset)


@router.get("/telecom_usage")
async def list_telecom(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                       offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, TelecomUsageEntry, device_id, limit, offset)


@router.get("/ride_hailing")
async def list_rides(device_id: Optional[str] = None, limit: int = Query(100, le=500),
                     offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, RideHailingEntry, device_id, limit, offset)


@router.get("/device_info")
async def list_device_info(device_id: Optional[str] = None, limit: int = Query(50, le=200),
                           offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, MetaDeviceInfo, device_id, limit, offset)


@router.get("/location_dwell")
async def list_location_dwell(device_id: Optional[str] = None, limit: int = Query(200, le=1000),
                              offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, LocationDwellEntry, device_id, limit, offset)


@router.get("/behavior")
async def list_behavior(device_id: Optional[str] = None, limit: int = Query(50, le=200),
                        offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, BehaviorScoreEntry, device_id, limit, offset)


@router.get("/installed_apps")
async def list_installed_apps(device_id: Optional[str] = None, limit: int = Query(500, le=2000),
                              offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, InstalledAppEntry, device_id, limit, offset)


@router.get("/contacts")
async def list_contacts(device_id: Optional[str] = None, limit: int = Query(500, le=2000),
                        offset: int = 0, db: Session = Depends(get_db)):
    return _paginated(db, ContactEntry, device_id, limit, offset)


@router.get("/summary")
async def summary(device_id: Optional[str] = None, db: Session = Depends(get_db)):
    """Return record counts and latest behavior score for a device."""
    counts: Dict[str, int] = {}
    for name, model_cls in METADATA_MODEL_MAP.items():
        q = db.query(func.count(model_cls.id))
        if device_id:
            q = q.filter(model_cls.device_id == device_id)
        counts[name] = q.scalar() or 0

    latest_behavior = None
    q = db.query(BehaviorScoreEntry)
    if device_id:
        q = q.filter(BehaviorScoreEntry.device_id == device_id)
    row = q.order_by(desc(BehaviorScoreEntry.created_at)).first()
    if row:
        latest_behavior = _row_to_dict(row)

    latest_device_info = None
    q2 = db.query(MetaDeviceInfo)
    if device_id:
        q2 = q2.filter(MetaDeviceInfo.device_id == device_id)
    row2 = q2.order_by(desc(MetaDeviceInfo.created_at)).first()
    if row2:
        latest_device_info = _row_to_dict(row2)

    latest_sim = None
    q3 = db.query(SimHistoryEntry)
    if device_id:
        q3 = q3.filter(SimHistoryEntry.device_id == device_id)
    row3 = q3.order_by(desc(SimHistoryEntry.created_at)).first()
    if row3:
        latest_sim = _row_to_dict(row3)

    return {
        "device_id": device_id,
        "counts": counts,
        "latest_behavior": latest_behavior,
        "latest_device_info": latest_device_info,
        "latest_sim": latest_sim,
    }


@router.get("/devices")
async def list_devices_with_metadata(db: Session = Depends(get_db)):
    """Distinct device_ids that have ever sent metadata, with counts."""
    # union of distinct device_ids from a few primary tables
    ids = set()
    for model_cls in (CallLogEntry, SmsEntry, MetaDeviceInfo, LocationDwellEntry):
        rows = db.query(model_cls.device_id).distinct().all()
        ids.update(r[0] for r in rows if r[0])
    return {"device_ids": sorted(ids)}


@router.delete("/{data_type}")
async def delete_metadata(data_type: str, device_id: Optional[str] = None,
                          db: Session = Depends(get_db)):
    """Delete all rows for a metadata type. If device_id is given, only that
    device's rows are removed; otherwise every row of that type is wiped."""
    model_cls = METADATA_MODEL_MAP.get(data_type)
    if not model_cls:
        raise HTTPException(status_code=400, detail=f"Unknown metadata type: {data_type}")
    q = db.query(model_cls)
    if device_id:
        q = q.filter(model_cls.device_id == device_id)
    deleted = q.delete(synchronize_session=False)
    db.commit()
    return {"status": "ok", "deleted": deleted}
