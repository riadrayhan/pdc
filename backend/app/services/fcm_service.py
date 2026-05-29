import firebase_admin
from firebase_admin import credentials, messaging
from typing import Dict, Any, Optional, List
import logging
import os

from app.core.config import settings

logger = logging.getLogger(__name__)

# Initialize Firebase
_firebase_initialized = False


def init_firebase():
    global _firebase_initialized
    if _firebase_initialized:
        return
    
    try:
        cred_path = settings.FIREBASE_CREDENTIALS_PATH
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred)
            _firebase_initialized = True
            logger.info("Firebase initialized successfully")
        else:
            logger.warning(f"Firebase credentials not found at {cred_path}")
    except Exception as e:
        logger.error(f"Failed to initialize Firebase: {e}")


class FCMService:
    """Firebase Cloud Messaging Service for sending push notifications to devices"""
    
    @staticmethod
    def send_command(
        fcm_token: str,
        command_type: str,
        payload: Dict[str, Any],
        command_id: str
    ) -> Optional[str]:
        """
        Send a command to a device via FCM
        
        Args:
            fcm_token: Device's FCM registration token
            command_type: Type of command (lock, unlock, warning, etc.)
            payload: Command-specific data
            command_id: Unique command ID for tracking
            
        Returns:
            FCM message ID if successful, None otherwise
        """
        try:
            init_firebase()
            
            # Build the data message (data messages are always processed by the app)
            data = {
                "command_type": command_type,
                "command_id": str(command_id),
                **{k: str(v) for k, v in payload.items()}
            }
            
            message = messaging.Message(
                data=data,
                token=fcm_token,
                android=messaging.AndroidConfig(
                    priority="high",
                    ttl=86400,  # 24 hours
                    direct_boot_ok=True,  # Deliver even in direct boot mode
                )
            )
            
            response = messaging.send(message)
            logger.info(f"FCM message sent successfully: {response}")
            return response
            
        except messaging.UnregisteredError:
            logger.warning(f"FCM token is invalid or unregistered")
            return None
        except Exception as e:
            logger.error(f"Failed to send FCM message: {e}")
            return None
    
    @staticmethod
    def send_lock_command(
        fcm_token: str,
        command_id: str,
        message: str = "Device locked due to pending payment",
        contact_number: str = ""
    ) -> Optional[str]:
        """Send a lock command to device"""
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="LOCK",
            payload={
                "message": message,
                "contact_number": contact_number,
                "allow_emergency": "true"
            },
            command_id=command_id
        )
    
    @staticmethod
    def send_unlock_command(fcm_token: str, command_id: str) -> Optional[str]:
        """Send an unlock command to device"""
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="UNLOCK",
            payload={},
            command_id=command_id
        )
    
    @staticmethod
    def send_warning(
        fcm_token: str,
        command_id: str,
        title: str,
        message: str,
        due_date: str = "",
        amount: str = ""
    ) -> Optional[str]:
        """Send a warning notification to device"""
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="WARNING",
            payload={
                "title": title,
                "message": message,
                "due_date": due_date,
                "amount": amount
            },
            command_id=command_id
        )
    
    @staticmethod
    def send_wipe_command(fcm_token: str, command_id: str) -> Optional[str]:
        """Send a factory reset command to device"""
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="WIPE",
            payload={},
            command_id=command_id
        )
    
    @staticmethod
    def send_sync_command(fcm_token: str, command_id: str) -> Optional[str]:
        """Send a sync request to device to report its status"""
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="SYNC",
            payload={},
            command_id=command_id
        )
    
    @staticmethod
    def send_bulk_command(
        fcm_tokens: List[str],
        command_type: str,
        payload: Dict[str, Any],
        command_ids: List[str]
    ) -> Dict[str, Any]:
        """
        Send command to multiple devices
        
        Returns:
            Dict with success_count, failure_count, and responses
        """
        results = {
            "success_count": 0,
            "failure_count": 0,
            "responses": []
        }
        
        for token, cmd_id in zip(fcm_tokens, command_ids):
            response = FCMService.send_command(token, command_type, payload, cmd_id)
            if response:
                results["success_count"] += 1
                results["responses"].append({"command_id": cmd_id, "message_id": response})
            else:
                results["failure_count"] += 1
                results["responses"].append({"command_id": cmd_id, "error": "Failed to send"})
        
        return results

    @staticmethod
    def send_update_command(
        fcm_token: str,
        command_id: str,
        apk_url: str = "https://rr-locker-api.onrender.com/api/v1/app/download",
        target_version: str = "",
        force: bool = False
    ) -> Optional[str]:
        """Send an app update command to device.
        Device will download APK and silently install via Device Owner PackageInstaller."""
        payload = {
            "apk_url": apk_url,
            "force": str(force).lower(),
        }
        if target_version:
            payload["target_version"] = target_version
        return FCMService.send_command(
            fcm_token=fcm_token,
            command_type="UPDATE_APP",
            payload=payload,
            command_id=command_id
        )
