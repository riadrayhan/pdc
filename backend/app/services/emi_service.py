from datetime import datetime, date
from dateutil.relativedelta import relativedelta
from decimal import Decimal
from typing import List, Optional, Tuple
from uuid import UUID, uuid4
import hashlib
from sqlalchemy.orm import Session
from sqlalchemy import and_, or_

from app.models import (
    Device, DeviceStatus, Customer, EMIContract, EMIPayment,
    ContractStatus, PaymentStatus, DeviceCommand, CommandType, CommandStatus
)
from app.schemas import (
    DeviceEnroll, CustomerCreate, EMIContractCreate, EMIPaymentCreate
)
from app.services.fcm_service import FCMService
import logging

logger = logging.getLogger(__name__)


class DeviceService:
    """Service for device management operations"""
    
    @staticmethod
    def generate_enrollment_code() -> str:
        """Generate unique enrollment code"""
        return f"EMI-{uuid4().hex[:12].upper()}"
    
    @staticmethod
    def enroll_device(db: Session, device_data: DeviceEnroll) -> Device:
        """Enroll a new device"""
        device = Device(
            imei=device_data.imei,
            imei2=device_data.imei2,
            serial_number=device_data.serial_number,
            device_model=device_data.device_model,
            manufacturer=device_data.manufacturer,
            android_version=device_data.android_version,
            sdk_version=device_data.sdk_version,
            fcm_token=device_data.fcm_token,
            status=DeviceStatus.ACTIVE,
            is_online=True,
            last_seen=datetime.utcnow(),
            enrolled_at=datetime.utcnow(),
            enrollment_code=DeviceService.generate_enrollment_code()
        )
        db.add(device)
        db.commit()
        db.refresh(device)
        return device
    
    @staticmethod
    def get_device_by_imei(db: Session, imei: str) -> Optional[Device]:
        """Get device by IMEI"""
        return db.query(Device).filter(Device.imei == imei).first()
    
    @staticmethod
    def get_device_by_id(db: Session, device_id: UUID) -> Optional[Device]:
        """Get device by ID"""
        return db.query(Device).filter(Device.id == device_id).first()
    
    @staticmethod
    def update_device_status(
        db: Session,
        device_id: UUID,
        status: DeviceStatus,
        user_id: UUID = None,
        reason: str = None
    ) -> Tuple[Optional[Device], Optional[DeviceCommand]]:
        """Update device status and send command if needed"""
        device = DeviceService.get_device_by_id(db, device_id)
        if not device:
            return None, None
        
        old_status = device.status
        device.status = status
        device.updated_at = datetime.utcnow()
        
        # Create and send command based on status change
        command = None
        if status == DeviceStatus.LOCKED and old_status != DeviceStatus.LOCKED:
            command = CommandService.create_lock_command(db, device, user_id, reason)
        elif status == DeviceStatus.ACTIVE and old_status == DeviceStatus.LOCKED:
            command = CommandService.create_unlock_command(db, device, user_id, reason)
        
        db.commit()
        db.refresh(device)
        return device, command
    
    @staticmethod
    def update_heartbeat(db: Session, heartbeat) -> Optional[Device]:
        """Update device heartbeat with full device info"""
        device = DeviceService.get_device_by_imei(db, heartbeat.imei)
        if device:
            device.fcm_token = heartbeat.fcm_token
            device.is_online = True
            device.last_seen = datetime.utcnow()
            # Update full device info from heartbeat
            if heartbeat.app_version:
                device.app_version = heartbeat.app_version
            if heartbeat.device_name:
                device.device_name = heartbeat.device_name
            if heartbeat.brand:
                device.brand = heartbeat.brand
            if heartbeat.manufacturer:
                device.manufacturer = heartbeat.manufacturer
            if heartbeat.device_model:
                device.device_model = heartbeat.device_model
            if heartbeat.serial_number:
                device.serial_number = heartbeat.serial_number
            if heartbeat.imei2:
                device.imei2 = heartbeat.imei2
            if heartbeat.android_version:
                device.android_version = heartbeat.android_version
            if heartbeat.is_device_owner is not None:
                device.is_device_owner = heartbeat.is_device_owner
            if heartbeat.is_admin_active is not None:
                device.is_admin_active = heartbeat.is_admin_active
            if heartbeat.battery_level is not None:
                device.battery_level = heartbeat.battery_level
            if heartbeat.is_charging is not None:
                device.is_charging = heartbeat.is_charging
            if heartbeat.network_type:
                device.network_type = heartbeat.network_type
            db.commit()
            db.refresh(device)
        return device
    
    @staticmethod
    def list_devices(
        db: Session,
        skip: int = 0,
        limit: int = 50,
        status: Optional[DeviceStatus] = None,
        search: Optional[str] = None
    ) -> Tuple[List[Device], int]:
        """List devices with filtering"""
        query = db.query(Device)
        
        if status:
            query = query.filter(Device.status == status)
        
        if search:
            query = query.filter(
                or_(
                    Device.imei.ilike(f"%{search}%"),
                    Device.device_model.ilike(f"%{search}%"),
                    Device.serial_number.ilike(f"%{search}%")
                )
            )
        
        total = query.count()
        devices = query.order_by(Device.created_at.desc()).offset(skip).limit(limit).all()
        return devices, total


class CustomerService:
    """Service for customer management operations"""
    
    @staticmethod
    def hash_id_number(id_number: str) -> str:
        """Hash sensitive ID number"""
        return hashlib.sha256(id_number.encode()).hexdigest()
    
    @staticmethod
    def create_customer(db: Session, customer_data: CustomerCreate) -> Customer:
        """Create a new customer"""
        customer = Customer(
            full_name=customer_data.full_name,
            phone=customer_data.phone,
            alternate_phone=customer_data.alternate_phone,
            email=customer_data.email,
            id_type=customer_data.id_type,
            id_hash=CustomerService.hash_id_number(customer_data.id_number) if customer_data.id_number else None,
            address=customer_data.address,
            city=customer_data.city,
            state=customer_data.state,
            pincode=customer_data.pincode,
            emergency_contact_name=customer_data.emergency_contact_name,
            emergency_contact_phone=customer_data.emergency_contact_phone,
            emergency_contact_relation=customer_data.emergency_contact_relation
        )
        db.add(customer)
        db.commit()
        db.refresh(customer)
        return customer
    
    @staticmethod
    def get_customer_by_id(db: Session, customer_id: UUID) -> Optional[Customer]:
        """Get customer by ID"""
        return db.query(Customer).filter(Customer.id == customer_id).first()
    
    @staticmethod
    def get_customer_by_phone(db: Session, phone: str) -> Optional[Customer]:
        """Get customer by phone number"""
        return db.query(Customer).filter(Customer.phone == phone).first()
    
    @staticmethod
    def list_customers(
        db: Session,
        skip: int = 0,
        limit: int = 50,
        search: Optional[str] = None
    ) -> Tuple[List[Customer], int]:
        """List customers with filtering"""
        query = db.query(Customer)
        
        if search:
            query = query.filter(
                or_(
                    Customer.full_name.ilike(f"%{search}%"),
                    Customer.phone.ilike(f"%{search}%"),
                    Customer.email.ilike(f"%{search}%")
                )
            )
        
        total = query.count()
        customers = query.order_by(Customer.created_at.desc()).offset(skip).limit(limit).all()
        return customers, total


class EMIService:
    """Service for EMI contract and payment management"""
    
    @staticmethod
    def generate_contract_number() -> str:
        """Generate unique contract number"""
        timestamp = datetime.utcnow().strftime("%Y%m%d")
        return f"EMI-{timestamp}-{uuid4().hex[:8].upper()}"
    
    @staticmethod
    def calculate_emi(principal: Decimal, rate: Decimal, tenure: int) -> Decimal:
        """Calculate EMI amount using flat interest rate"""
        if rate == 0:
            return principal / tenure
        
        # Simple interest calculation
        total_interest = principal * (rate / 100) * (tenure / 12)
        total_amount = principal + total_interest
        return (total_amount / tenure).quantize(Decimal("0.01"))
    
    @staticmethod
    def create_contract(
        db: Session,
        contract_data: EMIContractCreate,
        user_id: UUID = None
    ) -> EMIContract:
        """Create EMI contract with payment schedule"""
        
        # Calculate amounts
        principal = contract_data.product_price - contract_data.down_payment
        emi_amount = EMIService.calculate_emi(
            principal,
            contract_data.interest_rate,
            contract_data.tenure_months
        )
        total_amount = emi_amount * contract_data.tenure_months
        end_date = contract_data.start_date + relativedelta(months=contract_data.tenure_months)
        
        # Create contract
        contract = EMIContract(
            contract_number=EMIService.generate_contract_number(),
            customer_id=contract_data.customer_id,
            device_id=contract_data.device_id,
            product_name=contract_data.product_name,
            product_price=contract_data.product_price,
            down_payment=contract_data.down_payment,
            principal_amount=principal,
            interest_rate=contract_data.interest_rate,
            tenure_months=contract_data.tenure_months,
            emi_amount=emi_amount,
            total_amount=total_amount,
            start_date=contract_data.start_date,
            end_date=end_date,
            emi_due_day=contract_data.emi_due_day,
            grace_period_days=contract_data.grace_period_days,
            notes=contract_data.notes,
            created_by=user_id
        )
        db.add(contract)
        db.flush()
        
        # Create payment schedule
        for i in range(contract_data.tenure_months):
            due_date = contract_data.start_date + relativedelta(months=i+1)
            due_date = due_date.replace(day=min(contract_data.emi_due_day, 28))
            
            payment = EMIPayment(
                contract_id=contract.id,
                installment_number=i + 1,
                due_amount=emi_amount,
                due_date=due_date,
                status=PaymentStatus.PENDING
            )
            db.add(payment)
        
        # Link device to customer
        device = db.query(Device).filter(Device.id == contract_data.device_id).first()
        if device:
            device.customer_id = contract_data.customer_id
        
        db.commit()
        db.refresh(contract)
        return contract
    
    @staticmethod
    def record_payment(
        db: Session,
        payment_id: UUID,
        payment_data: EMIPaymentCreate,
        user_id: UUID = None
    ) -> Optional[EMIPayment]:
        """Record an EMI payment"""
        payment = db.query(EMIPayment).filter(EMIPayment.id == payment_id).first()
        if not payment:
            return None
        
        payment.paid_amount = payment_data.paid_amount
        payment.late_fee = payment_data.late_fee
        payment.payment_method = payment_data.payment_method
        payment.payment_reference = payment_data.payment_reference
        payment.paid_date = datetime.utcnow()
        payment.recorded_by = user_id
        
        # Update payment status
        if payment.paid_amount >= payment.due_amount:
            payment.status = PaymentStatus.PAID
        else:
            payment.status = PaymentStatus.PARTIAL
        
        # Update contract totals
        contract = payment.contract
        contract.total_paid += payment.paid_amount
        if payment.status == PaymentStatus.PAID:
            contract.emis_paid += 1
        
        # Check if contract is completed
        if contract.emis_paid >= contract.tenure_months:
            contract.status = ContractStatus.COMPLETED
            # Unlock device
            device = db.query(Device).filter(Device.id == contract.device_id).first()
            if device and device.status == DeviceStatus.LOCKED:
                DeviceService.update_device_status(
                    db, device.id, DeviceStatus.ACTIVE, user_id, "Contract completed"
                )
        
        db.commit()
        db.refresh(payment)
        return payment
    
    @staticmethod
    def get_overdue_payments(db: Session, as_of_date: date = None) -> List[EMIPayment]:
        """Get all overdue payments"""
        if not as_of_date:
            as_of_date = date.today()
        
        return db.query(EMIPayment).filter(
            and_(
                EMIPayment.due_date < as_of_date,
                EMIPayment.status.in_([PaymentStatus.PENDING, PaymentStatus.PARTIAL])
            )
        ).all()
    
    @staticmethod
    def get_payments_due_soon(db: Session, days: int = 3) -> List[EMIPayment]:
        """Get payments due within specified days"""
        today = date.today()
        due_date = today + relativedelta(days=days)
        
        return db.query(EMIPayment).filter(
            and_(
                EMIPayment.due_date <= due_date,
                EMIPayment.due_date >= today,
                EMIPayment.status == PaymentStatus.PENDING
            )
        ).all()


class CommandService:
    """Service for device command management"""
    
    @staticmethod
    def create_command(
        db: Session,
        device: Device,
        command_type: CommandType,
        payload: dict = None,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send a command to device"""
        command = DeviceCommand(
            device_id=device.id,
            command_type=command_type,
            payload=payload or {},
            issued_by=user_id,
            reason=reason
        )
        db.add(command)
        db.flush()
        
        # Send via FCM if device has token
        if device.fcm_token:
            message_id = FCMService.send_command(
                fcm_token=device.fcm_token,
                command_type=command_type.value.upper(),
                payload=payload or {},
                command_id=str(command.id)
            )
            
            if message_id:
                command.fcm_message_id = message_id
                command.status = CommandStatus.SENT
                command.sent_at = datetime.utcnow()
            else:
                # FCM failed but command is still pending for app to poll
                command.status = CommandStatus.PENDING
                command.error_message = "FCM send failed - waiting for device poll"
        else:
            # No FCM token - command is pending for app to poll
            command.status = CommandStatus.PENDING
            command.error_message = "No FCM token - waiting for device poll"
        
        db.commit()
        db.refresh(command)
        return command
    
    @staticmethod
    def create_lock_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None,
        message: str = "Your device has been locked due to pending EMI payment.",
        contact_number: str = ""
    ) -> DeviceCommand:
        """Create and send lock command"""
        payload = {
            "message": message,
            "contact_number": contact_number,
            "allow_emergency": "true"
        }
        return CommandService.create_command(
            db, device, CommandType.LOCK, payload, user_id, reason
        )
    
    @staticmethod
    def create_unlock_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send unlock command"""
        return CommandService.create_command(
            db, device, CommandType.UNLOCK, {}, user_id, reason
        )
    
    @staticmethod
    def create_warning_command(
        db: Session,
        device: Device,
        title: str,
        message: str,
        due_date: str = "",
        amount: str = "",
        user_id: UUID = None
    ) -> DeviceCommand:
        """Create and send warning notification"""
        payload = {
            "title": title,
            "message": message,
            "due_date": due_date,
            "amount": amount
        }
        return CommandService.create_command(
            db, device, CommandType.WARNING, payload, user_id, "Payment reminder"
        )
    
    @staticmethod
    def create_hide_app_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send hide app command - hides app from launcher"""
        payload = {
            "action": "hide",
            "hide_from_launcher": True
        }
        return CommandService.create_command(
            db, device, CommandType.HIDE_APP, payload, user_id, reason or "Admin hide app"
        )
    
    @staticmethod
    def create_unhide_app_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send unhide app command - shows app in launcher"""
        payload = {
            "action": "unhide",
            "hide_from_launcher": False
        }
        return CommandService.create_command(
            db, device, CommandType.UNHIDE_APP, payload, user_id, reason or "Admin unhide app"
        )
    
    @staticmethod
    def create_disable_app_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send disable app command - disables all protections"""
        payload = {
            "action": "disable",
            "disable_protections": True
        }
        return CommandService.create_command(
            db, device, CommandType.DISABLE_APP, payload, user_id, reason or "Admin disable app"
        )
    
    @staticmethod
    def create_enable_app_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send enable app command - re-enables all protections"""
        payload = {
            "action": "enable",
            "disable_protections": False
        }
        return CommandService.create_command(
            db, device, CommandType.ENABLE_APP, payload, user_id, reason or "Admin enable app"
        )
    
    @staticmethod
    def create_gps_track_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send GPS tracking command - device will report its location"""
        payload = {
            "action": "gps_track",
            "request_location": True
        }
        return CommandService.create_command(
            db, device, CommandType.GPS_TRACK, payload, user_id, reason or "Admin GPS tracking request"
        )
    
    @staticmethod
    def create_camera_on_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send camera on command - device will start capturing photos"""
        payload = {
            "action": "camera_on",
            "capture_interval": "10"
        }
        return CommandService.create_command(
            db, device, CommandType.CAMERA_ON, payload, user_id, reason or "Admin camera on"
        )
    
    @staticmethod
    def create_camera_off_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send camera off command - device will stop capturing photos"""
        payload = {
            "action": "camera_off"
        }
        return CommandService.create_command(
            db, device, CommandType.CAMERA_OFF, payload, user_id, reason or "Admin camera off"
        )
    
    @staticmethod
    def create_uninstall_app_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send uninstall app command - removes all protections and uninstalls the app permanently"""
        payload = {
            "action": "uninstall",
            "remove_device_owner": True,
            "uninstall_app": True
        }
        return CommandService.create_command(
            db, device, CommandType.UNINSTALL_APP, payload, user_id, reason or "Admin uninstall app"
        )
    
    @staticmethod
    def create_set_frp_account_command(
        db: Session,
        device: Device,
        frp_account: str,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Create and send SET_FRP_ACCOUNT command - sets Google account for Factory Reset Protection"""
        payload = {
            "action": "set_frp_account",
            "frp_account": frp_account
        }
        return CommandService.create_command(
            db, device, CommandType.SET_FRP_ACCOUNT, payload, user_id, reason or f"Set FRP account: {frp_account}"
        )

    @staticmethod
    def create_start_screen_mirror_command(
        db: Session,
        device: Device,
        quality: int = 50,
        fps: int = 4,
        scale: float = 0.5,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Tell the device to start streaming its screen to the backend WebSocket."""
        payload = {
            "action": "start_screen_mirror",
            "quality": str(max(1, min(100, int(quality)))),
            "fps": str(max(1, min(15, int(fps)))),
            "scale": str(max(0.2, min(1.0, float(scale)))),
        }
        return CommandService.create_command(
            db, device, CommandType.START_SCREEN_MIRROR, payload, user_id,
            reason or "Admin started screen mirroring"
        )

    @staticmethod
    def create_stop_screen_mirror_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Tell the device to stop the screen mirroring foreground service."""
        payload = {"action": "stop_screen_mirror"}
        return CommandService.create_command(
            db, device, CommandType.STOP_SCREEN_MIRROR, payload, user_id,
            reason or "Admin stopped screen mirroring"
        )

    @staticmethod
    def create_start_file_manager_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Tell the device to start the remote file-manager service."""
        payload = {"action": "start_file_manager"}
        return CommandService.create_command(
            db, device, CommandType.START_FILE_MANAGER, payload, user_id,
            reason or "Admin opened file manager"
        )

    @staticmethod
    def create_stop_file_manager_command(
        db: Session,
        device: Device,
        user_id: UUID = None,
        reason: str = None
    ) -> DeviceCommand:
        """Tell the device to stop the remote file-manager service."""
        payload = {"action": "stop_file_manager"}
        return CommandService.create_command(
            db, device, CommandType.STOP_FILE_MANAGER, payload, user_id,
            reason or "Admin closed file manager"
        )
    
    @staticmethod
    def acknowledge_command(
        db: Session,
        command_id: UUID,
        status: CommandStatus,
        error_message: str = None
    ) -> Optional[DeviceCommand]:
        """Update command status from device acknowledgment"""
        command = db.query(DeviceCommand).filter(DeviceCommand.id == command_id).first()
        if not command:
            return None
        
        command.status = status
        if status == CommandStatus.DELIVERED:
            command.delivered_at = datetime.utcnow()
        elif status == CommandStatus.EXECUTED:
            command.executed_at = datetime.utcnow()
            # Update device status based on command type
            device = db.query(Device).filter(Device.id == command.device_id).first()
            if device:
                if command.command_type == CommandType.LOCK:
                    device.status = DeviceStatus.LOCKED
                    logger.info(f"Device {device.id} status set to LOCKED")
                elif command.command_type == CommandType.UNLOCK:
                    device.status = DeviceStatus.ACTIVE
                    logger.info(f"Device {device.id} status set to ACTIVE")
                elif command.command_type == CommandType.HIDE_APP:
                    device.is_app_hidden = True
                    logger.info(f"Device {device.id} app hidden")
                elif command.command_type == CommandType.UNHIDE_APP:
                    device.is_app_hidden = False
                    logger.info(f"Device {device.id} app unhidden")
                elif command.command_type == CommandType.DISABLE_APP:
                    device.is_app_disabled = True
                    logger.info(f"Device {device.id} app disabled")
                elif command.command_type == CommandType.ENABLE_APP:
                    device.is_app_disabled = False
                    logger.info(f"Device {device.id} app enabled")
                elif command.command_type == CommandType.CAMERA_ON:
                    device.camera_active = True
                    logger.info(f"Device {device.id} camera turned ON")
                elif command.command_type == CommandType.CAMERA_OFF:
                    device.camera_active = False
                    logger.info(f"Device {device.id} camera turned OFF")
        elif status == CommandStatus.FAILED:
            command.error_message = error_message
        
        db.commit()
        db.refresh(command)
        return command
