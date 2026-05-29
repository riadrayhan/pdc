from datetime import date, datetime, timedelta
from decimal import Decimal
import logging

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.models import (
    Device, DeviceStatus, EMIContract, EMIPayment,
    PaymentStatus, ContractStatus, DeviceCommand, CommandStatus
)
from app.services import CommandService, FCMService

logger = logging.getLogger(__name__)


@celery_app.task(name="app.tasks.emi_tasks.check_overdue_payments")
def check_overdue_payments():
    """
    Check for overdue payments and update their status
    Runs every hour
    """
    db = SessionLocal()
    try:
        today = date.today()
        
        # Find pending payments that are now overdue
        overdue_payments = db.query(EMIPayment).filter(
            EMIPayment.due_date < today,
            EMIPayment.status == PaymentStatus.PENDING
        ).all()
        
        count = 0
        for payment in overdue_payments:
            payment.status = PaymentStatus.OVERDUE
            count += 1
        
        db.commit()
        logger.info(f"Marked {count} payments as overdue")
        return {"marked_overdue": count}
        
    except Exception as e:
        logger.error(f"Error checking overdue payments: {e}")
        db.rollback()
        raise
    finally:
        db.close()


@celery_app.task(name="app.tasks.emi_tasks.send_payment_reminders")
def send_payment_reminders():
    """
    Send payment reminder notifications for upcoming due dates
    Runs daily
    """
    db = SessionLocal()
    try:
        today = date.today()
        reminder_date = today + timedelta(days=3)  # 3 days before due date
        
        # Find payments due within 3 days
        upcoming_payments = db.query(EMIPayment).filter(
            EMIPayment.due_date == reminder_date,
            EMIPayment.status == PaymentStatus.PENDING
        ).all()
        
        sent_count = 0
        for payment in upcoming_payments:
            contract = payment.contract
            device = db.query(Device).filter(Device.id == contract.device_id).first()
            
            if device and device.fcm_token:
                # Send warning notification
                command = CommandService.create_warning_command(
                    db, device,
                    title="EMI Payment Reminder",
                    message=f"Your EMI payment of ₹{payment.due_amount} is due on {payment.due_date}. Please pay on time to avoid device lock.",
                    due_date=str(payment.due_date),
                    amount=str(payment.due_amount)
                )
                sent_count += 1
        
        db.commit()
        logger.info(f"Sent {sent_count} payment reminders")
        return {"reminders_sent": sent_count}
        
    except Exception as e:
        logger.error(f"Error sending payment reminders: {e}")
        db.rollback()
        raise
    finally:
        db.close()


@celery_app.task(name="app.tasks.emi_tasks.auto_lock_overdue_devices")
def auto_lock_overdue_devices():
    """
    Automatically lock devices with overdue payments beyond grace period
    Runs every hour
    """
    db = SessionLocal()
    try:
        today = date.today()
        locked_count = 0
        
        # Get all active contracts
        contracts = db.query(EMIContract).filter(
            EMIContract.status == ContractStatus.ACTIVE
        ).all()
        
        for contract in contracts:
            # Check for overdue payments beyond grace period
            grace_cutoff = today - timedelta(days=contract.grace_period_days)
            
            overdue_payments = db.query(EMIPayment).filter(
                EMIPayment.contract_id == contract.id,
                EMIPayment.due_date < grace_cutoff,
                EMIPayment.status.in_([PaymentStatus.PENDING, PaymentStatus.OVERDUE, PaymentStatus.PARTIAL])
            ).first()
            
            if overdue_payments:
                device = db.query(Device).filter(Device.id == contract.device_id).first()
                
                if device and device.status != DeviceStatus.LOCKED:
                    # Lock the device
                    customer = contract.customer
                    
                    command = CommandService.create_lock_command(
                        db, device,
                        reason=f"Auto-lock: Payment overdue since {overdue_payments.due_date}",
                        message=f"Device locked due to overdue payment. Amount due: ₹{overdue_payments.due_amount}. Contact support to unlock.",
                        contact_number=""  # Add your support number
                    )
                    
                    device.status = DeviceStatus.LOCKED
                    locked_count += 1
                    
                    logger.info(f"Auto-locked device {device.imei} for contract {contract.contract_number}")
        
        db.commit()
        logger.info(f"Auto-locked {locked_count} devices")
        return {"devices_locked": locked_count}
        
    except Exception as e:
        logger.error(f"Error in auto-lock task: {e}")
        db.rollback()
        raise
    finally:
        db.close()


@celery_app.task(name="app.tasks.emi_tasks.sync_device_status")
def sync_device_status():
    """
    Send sync commands to devices that haven't reported in a while
    Runs every 30 minutes
    """
    db = SessionLocal()
    try:
        # Find devices that haven't been seen in 6 hours
        cutoff = datetime.utcnow() - timedelta(hours=6)
        
        stale_devices = db.query(Device).filter(
            Device.status.in_([DeviceStatus.ACTIVE, DeviceStatus.LOCKED, DeviceStatus.WARNING]),
            Device.last_seen < cutoff,
            Device.fcm_token.isnot(None)
        ).all()
        
        sync_count = 0
        for device in stale_devices:
            if device.fcm_token:
                FCMService.send_sync_command(device.fcm_token, str(device.id))
                device.is_online = False
                sync_count += 1
        
        db.commit()
        logger.info(f"Sent sync requests to {sync_count} stale devices")
        return {"sync_requests_sent": sync_count}
        
    except Exception as e:
        logger.error(f"Error in sync task: {e}")
        db.rollback()
        raise
    finally:
        db.close()


@celery_app.task(name="app.tasks.emi_tasks.retry_failed_commands")
def retry_failed_commands():
    """
    Retry commands that failed to send
    """
    db = SessionLocal()
    try:
        # Find failed commands within last 24 hours
        cutoff = datetime.utcnow() - timedelta(hours=24)
        
        failed_commands = db.query(DeviceCommand).filter(
            DeviceCommand.status == CommandStatus.FAILED,
            DeviceCommand.created_at > cutoff,
            DeviceCommand.retry_count < DeviceCommand.max_retries
        ).all()
        
        retry_count = 0
        for command in failed_commands:
            device = command.device
            if device and device.fcm_token:
                message_id = FCMService.send_command(
                    fcm_token=device.fcm_token,
                    command_type=command.command_type.value.upper(),
                    payload=command.payload,
                    command_id=str(command.id)
                )
                
                if message_id:
                    command.fcm_message_id = message_id
                    command.status = CommandStatus.SENT
                    command.sent_at = datetime.utcnow()
                    command.error_message = None
                    retry_count += 1
                
                command.retry_count = str(int(command.retry_count) + 1)
        
        db.commit()
        logger.info(f"Retried {retry_count} failed commands")
        return {"commands_retried": retry_count}
        
    except Exception as e:
        logger.error(f"Error retrying commands: {e}")
        db.rollback()
        raise
    finally:
        db.close()


@celery_app.task(name="app.tasks.emi_tasks.retry_undelivered_commands")
def retry_undelivered_commands():
    """
    Re-send commands stuck in PENDING/SENT state for >30 minutes.
    Acts as FCM delivery backup — if FCM silently drops a message,
    this ensures it gets resent.
    Runs every 15 minutes.
    """
    db = SessionLocal()
    try:
        stale_cutoff = datetime.utcnow() - timedelta(minutes=30)
        max_age = datetime.utcnow() - timedelta(hours=24)
        
        stale_commands = db.query(DeviceCommand).filter(
            DeviceCommand.status.in_([CommandStatus.PENDING, CommandStatus.SENT]),
            DeviceCommand.created_at < stale_cutoff,
            DeviceCommand.created_at > max_age,
        ).all()
        
        resent = 0
        for command in stale_commands:
            device = command.device
            if device and device.fcm_token:
                current_retries = int(command.retry_count or "0")
                max_retries = int(command.max_retries or "3")
                if current_retries >= max_retries:
                    command.status = CommandStatus.FAILED
                    command.error_message = "Max retries exceeded (undelivered)"
                    continue
                
                message_id = FCMService.send_command(
                    fcm_token=device.fcm_token,
                    command_type=command.command_type.value.upper(),
                    payload=command.payload or {},
                    command_id=str(command.id)
                )
                
                if message_id:
                    command.fcm_message_id = message_id
                    command.status = CommandStatus.SENT
                    command.sent_at = datetime.utcnow()
                    resent += 1
                
                command.retry_count = str(current_retries + 1)
        
        db.commit()
        logger.info(f"Re-sent {resent}/{len(stale_commands)} stale commands")
        return {"commands_resent": resent, "stale_total": len(stale_commands)}
        
    except Exception as e:
        logger.error(f"Error retrying undelivered commands: {e}")
        db.rollback()
        raise
    finally:
        db.close()
