from pydantic_settings import BaseSettings
from typing import Optional
import os


class Settings(BaseSettings):
    # App Settings
    APP_NAME: str = "RR Locker API"
    VERSION: str = "1.0.0"
    DEBUG: bool = True
    APP_VERSION: str = "1.0.0"
    
    # Database (SQLite)
    DATABASE_URL: str = "sqlite:///./emi_locker.db"
    
    # Redis
    REDIS_URL: str = "redis://localhost:6379/0"
    
    # JWT Settings
    SECRET_KEY: str = "your-super-secret-key-change-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7
    
    # Firebase
    FIREBASE_CREDENTIALS_PATH: str = "firebase-credentials.json"
    
    # EMI Settings
    DEFAULT_GRACE_PERIOD_DAYS: int = 7
    WARNING_DAYS_BEFORE_DUE: int = 3
    
    # APK Download Settings (for auto-reinstall after factory reset)
    APK_DOWNLOAD_URL: str = "https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download"
    APK_CHECKSUM: str = ""
    
    # CORS
    CORS_ORIGINS: list = ["https://riadrayhan111-rr-locker-dashboard.static.hf.space", "https://riadrayhan111-rr-locker-api.hf.space", "http://localhost:3000", "http://localhost:5173"]
    
    # Android Management API (AMAPI)
    AMAPI_SERVICE_ACCOUNT_JSON: str = ""  # Base64-encoded service account JSON or file path
    AMAPI_PROJECT_ID: str = "blooger-project"  # Google Cloud project ID
    AMAPI_ENTERPRISE_NAME: str = "enterprises/LC015xh0ii"  # Set after enterprise creation
    
    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
