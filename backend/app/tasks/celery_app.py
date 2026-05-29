from celery import Celery
from app.core.config import settings

celery_app = Celery(
    "rr_locker",
    broker=settings.REDIS_URL,
    backend=settings.REDIS_URL,
    include=["app.tasks.emi_tasks"]
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=300,  # 5 minutes
    worker_prefetch_multiplier=1,
)

# Celery Beat Schedule for periodic tasks
celery_app.conf.beat_schedule = {
    "check-overdue-payments": {
        "task": "app.tasks.emi_tasks.check_overdue_payments",
        "schedule": 3600.0,  # Every hour
    },
    "send-payment-reminders": {
        "task": "app.tasks.emi_tasks.send_payment_reminders",
        "schedule": 86400.0,  # Every 24 hours
    },
    "auto-lock-overdue-devices": {
        "task": "app.tasks.emi_tasks.auto_lock_overdue_devices",
        "schedule": 3600.0,  # Every hour
    },
    "sync-device-status": {
        "task": "app.tasks.emi_tasks.sync_device_status",
        "schedule": 1800.0,  # Every 30 minutes
    },
    "retry-failed-commands": {
        "task": "app.tasks.emi_tasks.retry_failed_commands",
        "schedule": 900.0,  # Every 15 minutes
    },
    "retry-undelivered-commands": {
        "task": "app.tasks.emi_tasks.retry_undelivered_commands",
        "schedule": 900.0,  # Every 15 minutes
    },
}
