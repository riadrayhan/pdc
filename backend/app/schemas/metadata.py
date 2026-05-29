from pydantic import BaseModel, Field
from typing import Any, Dict, List, Optional


class MetadataBatch(BaseModel):
    """Payload posted by the Android app for a single data type."""
    type: str = Field(..., description="One of: call_logs, sms, location, sim_history, mobile_money, telecom_usage, ride_hailing, device_info, location_dwell, behavior_scores, installed_apps")
    device_id: str
    data: List[Dict[str, Any]]


class MetadataAck(BaseModel):
    status: str
    received: int
    stored: int
