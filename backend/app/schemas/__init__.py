from app.schemas.user import (
    UserBase, UserCreate, UserUpdate, UserResponse,
    Token, TokenRefresh, LoginRequest
)
from app.schemas.device import (
    DeviceBase, DeviceEnroll, DeviceUpdate, DeviceStatusUpdate,
    DeviceResponse, DeviceListResponse, DeviceHeartbeat, EnrollmentQRData
)
from app.schemas.customer import (
    CustomerBase, CustomerCreate, CustomerUpdate,
    CustomerResponse, CustomerListResponse
)
from app.schemas.emi import (
    EMIContractCreate, EMIContractUpdate, EMIContractResponse, EMIContractListResponse,
    EMIPaymentCreate, EMIPaymentResponse, EMIPaymentListResponse, OverdueReport
)
from app.schemas.command import (
    CommandCreate, CommandBulk, CommandResponse, CommandStatusUpdate,
    CommandAck, LockCommand, UnlockCommand, WarningCommand,
    HideAppCommand, UnhideAppCommand, DisableAppCommand, EnableAppCommand,
    GPSTrackCommand, CameraOnCommand, CameraOffCommand, UninstallAppCommand,
    SetFRPAccountCommand, SendMessageCommand, UpdateAppCommand, BulkUpdateAppCommand,
    StartScreenMirrorCommand, StopScreenMirrorCommand,
    StartFileManagerCommand, StopFileManagerCommand,
)

__all__ = [
    # User
    "UserBase", "UserCreate", "UserUpdate", "UserResponse",
    "Token", "TokenRefresh", "LoginRequest",
    # Device
    "DeviceBase", "DeviceEnroll", "DeviceUpdate", "DeviceStatusUpdate",
    "DeviceResponse", "DeviceListResponse", "DeviceHeartbeat", "EnrollmentQRData",
    # Customer
    "CustomerBase", "CustomerCreate", "CustomerUpdate",
    "CustomerResponse", "CustomerListResponse",
    # EMI
    "EMIContractCreate", "EMIContractUpdate", "EMIContractResponse", "EMIContractListResponse",
    "EMIPaymentCreate", "EMIPaymentResponse", "EMIPaymentListResponse", "OverdueReport",
    # Command
    "CommandCreate", "CommandBulk", "CommandResponse", "CommandStatusUpdate",
    "CommandAck", "LockCommand", "UnlockCommand", "WarningCommand",
    "HideAppCommand", "UnhideAppCommand",
    "DisableAppCommand", "EnableAppCommand",
    "GPSTrackCommand", "CameraOnCommand", "CameraOffCommand",
    "UninstallAppCommand", "SetFRPAccountCommand", "SendMessageCommand",
    "UpdateAppCommand", "BulkUpdateAppCommand",
    "StartScreenMirrorCommand", "StopScreenMirrorCommand",
    "StartFileManagerCommand", "StopFileManagerCommand",
]
