from app.models.user import User, UserRole
from app.models.device import Device, DeviceStatus
from app.models.customer import Customer
from app.models.emi import EMIContract, EMIPayment, ContractStatus, PaymentStatus
from app.models.command import DeviceCommand, CommandType, CommandStatus, AuditLog
from app.models.zte import ZTEConfig, ZTEEnrollmentEvent, ZTEProvisioningLog, ZTEEnrollmentStatus
# Importing the metadata module registers its tables on Base.metadata so they
# get created by create_tables() on startup.
from app.models import metadata as _metadata  # noqa: F401

__all__ = [
    "User",
    "UserRole",
    "Device",
    "DeviceStatus",
    "Customer",
    "EMIContract",
    "EMIPayment",
    "ContractStatus",
    "PaymentStatus",
    "DeviceCommand",
    "CommandType",
    "CommandStatus",
    "AuditLog",
    "ZTEConfig",
    "ZTEEnrollmentEvent",
    "ZTEProvisioningLog",
    "ZTEEnrollmentStatus",
]
