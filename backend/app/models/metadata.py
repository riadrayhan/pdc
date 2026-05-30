"""
Device metadata models — collected from devices for credit-risk analysis.

Tables created per data type. `device_id` references devices.id (UUID string) when
the device is enrolled, or falls back to Android-ID otherwise.
"""
import uuid
from datetime import datetime
from sqlalchemy import Column, String, DateTime, Integer, Float, Text, Index

from app.core.database import Base
from app.core.types import UUID


class CallLogEntry(Base):
    __tablename__ = "md_call_logs"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    number = Column(String(64))
    type = Column(String(16))         # INCOMING | OUTGOING | MISSED | REJECTED
    call_date = Column(String(32))    # ms timestamp from android
    duration = Column(String(16))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class SmsEntry(Base):
    __tablename__ = "md_sms"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    address = Column(String(64))
    body = Column(Text)
    sms_date = Column(String(32))
    type = Column(String(16))         # SENT | RECEIVED | DRAFT | OTHER
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class LocationEntry(Base):
    __tablename__ = "md_locations"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    latitude = Column(Float)
    longitude = Column(Float)
    accuracy = Column(Float)
    timestamp = Column(String(32))
    address = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class SimHistoryEntry(Base):
    __tablename__ = "md_sim_history"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    old_iccid = Column(String(64))
    new_iccid = Column(String(64))
    phone_number = Column(String(32))
    carrier = Column(String(64))
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class MobileMoneyEntry(Base):
    __tablename__ = "md_mobile_money"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    provider = Column(String(32))     # bKash | Nagad
    txn_type = Column(String(32))
    amount = Column(String(32))
    balance = Column(String(32))
    txn_id = Column(String(64))
    counter_party = Column(String(32))
    sender = Column(String(64))
    raw_sms = Column(Text)
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class TelecomUsageEntry(Base):
    __tablename__ = "md_telecom_usage"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    operator = Column(String(32))
    recharge_type = Column(String(32))
    amount = Column(String(32))
    balance = Column(String(32))
    sender = Column(String(64))
    raw_sms = Column(Text)
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class RideHailingEntry(Base):
    __tablename__ = "md_ride_hailing"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    provider = Column(String(32))     # Uber | Pathao
    ride_type = Column(String(32))
    amount = Column(String(32))
    trip_details = Column(Text)
    sender = Column(String(64))
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class MetaDeviceInfo(Base):
    __tablename__ = "md_device_info"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    android_id = Column(String(64))
    brand = Column(String(64))
    model = Column(String(64))
    manufacturer = Column(String(64))
    device = Column(String(64))
    hardware = Column(String(64))
    os_version = Column(String(32))
    api_level = Column(String(8))
    security_patch = Column(String(32))
    build_fingerprint = Column(String(255))
    first_install_time = Column(String(32))
    uptime_days = Column(String(16))
    is_rooted = Column(String(8))
    sim_swap_count = Column(String(8))
    factory_reset_indicator = Column(String(8))
    screen_info = Column(String(64))
    ram_info = Column(String(64))
    storage_info = Column(String(64))
    battery_info = Column(String(64))
    network_type = Column(String(32))
    timezone = Column(String(64))
    language = Column(String(8))
    country = Column(String(8))
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class LocationDwellEntry(Base):
    __tablename__ = "md_location_dwell"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    latitude = Column(Float)
    longitude = Column(Float)
    accuracy = Column(Float)
    address = Column(Text)
    timestamp = Column(String(32))
    dwell_minutes = Column(Integer, default=0)
    location_type = Column(String(32))   # NIGHT_HOME | WORK_HOURS | WEEKEND | ...
    visit_count = Column(Integer, default=1)
    event_type = Column(String(16))      # ARRIVAL | DWELL
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class BehaviorScoreEntry(Base):
    __tablename__ = "md_behavior_scores"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    total_calls = Column(Integer)
    incoming_calls = Column(Integer)
    outgoing_calls = Column(Integer)
    missed_calls = Column(Integer)
    total_duration = Column(Integer)
    night_calls = Column(Integer)
    weekend_calls = Column(Integer)
    unique_call_contacts = Column(Integer)
    call_regularity = Column(String(16))
    in_out_ratio = Column(String(16))
    night_ratio = Column(String(16))
    weekend_ratio = Column(String(16))
    avg_call_duration = Column(String(16))
    contact_diversity = Column(String(16))
    total_sms = Column(Integer)
    sent_sms = Column(Integer)
    received_sms = Column(Integer)
    unique_sms_contacts = Column(Integer)
    network_size = Column(Integer)
    unique_locations = Column(Integer)
    total_mfs_txns = Column(Integer)
    total_mfs_volume = Column(String(32))
    total_recharges = Column(Integer)
    total_recharge_amount = Column(String(32))
    mfs_activity_score = Column(String(16))
    recharge_frequency = Column(String(16))
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class InstalledAppEntry(Base):
    __tablename__ = "md_installed_apps"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    package_name = Column(String(128))
    app_name = Column(String(128))
    category = Column(String(32))
    version = Column(String(32))
    install_date = Column(String(16))
    last_update = Column(String(16))
    status = Column(String(16))          # INSTALLED | NOT_INSTALLED | SUMMARY
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


class ContactEntry(Base):
    __tablename__ = "md_contacts"
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    device_id = Column(String(64), index=True, nullable=False)
    name = Column(String(128))
    number = Column(String(64))
    normalized_number = Column(String(64))
    type = Column(String(16))            # HOME | MOBILE | WORK | MAIN | OTHER
    times_contacted = Column(String(16))
    last_contacted = Column(String(32))
    account_type = Column(String(64))
    timestamp = Column(String(32))
    created_at = Column(DateTime, default=datetime.utcnow, index=True)


# Map JSON `type` value from Android sync payload to the SQLAlchemy model.
METADATA_MODEL_MAP = {
    "call_logs": CallLogEntry,
    "sms": SmsEntry,
    "location": LocationEntry,
    "sim_history": SimHistoryEntry,
    "mobile_money": MobileMoneyEntry,
    "telecom_usage": TelecomUsageEntry,
    "ride_hailing": RideHailingEntry,
    "device_info": MetaDeviceInfo,
    "location_dwell": LocationDwellEntry,
    "behavior_scores": BehaviorScoreEntry,
    "installed_apps": InstalledAppEntry,
    "contacts": ContactEntry,
}


# Map Android column names that need renaming to match the SQLAlchemy columns
# (mainly to dodge SQL reserved words).
COLUMN_RENAME = {
    "date": "call_date",   # call_logs / sms send "date"
}
