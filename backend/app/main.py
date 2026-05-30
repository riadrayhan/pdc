from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
import logging
import os
import threading

from app.core.config import settings
from app.core.database import create_tables, SessionLocal, engine
from app.api import api_router
from app.services import init_firebase
from sqlalchemy import text, inspect

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


def migrate_database():
    """Add missing columns to existing tables (simple migration)"""
    inspector = inspect(engine)
    
    # Columns to add to 'devices' table if missing
    new_columns = {
        "last_latitude": "VARCHAR(50)",
        "last_longitude": "VARCHAR(50)",
        "last_location_time": "TIMESTAMP",
        "last_location_address": "TEXT",
        "camera_active": "BOOLEAN DEFAULT FALSE",
        "last_photo_url": "TEXT",
        "last_photo_time": "TIMESTAMP",
        "is_app_hidden": "BOOLEAN DEFAULT FALSE",
        "is_app_disabled": "BOOLEAN DEFAULT FALSE",
        "battery_level": "INTEGER",
        "is_charging": "BOOLEAN",
        "network_type": "VARCHAR(20)",
    }
    
    if "devices" in inspector.get_table_names():
        existing_columns = {col["name"] for col in inspector.get_columns("devices")}
        
        with engine.begin() as conn:
            for col_name, col_type in new_columns.items():
                if col_name not in existing_columns:
                    try:
                        conn.execute(text(f"ALTER TABLE devices ADD COLUMN {col_name} {col_type}"))
                        logger.info(f"Added column: devices.{col_name}")
                    except Exception as e:
                        logger.warning(f"Could not add column {col_name}: {e}")
    
    logger.info("Database migration check complete")


def create_default_admin():
    """Create default admin user if not exists"""
    from app.models.user import User, UserRole
    from app.core.security import get_password_hash
    
    db = SessionLocal()
    try:
        # Check if admin exists
        admin = db.query(User).filter(User.username == "admin").first()
        if not admin:
            admin = User(
                email="admin@emilocker.com",
                username="admin",
                hashed_password=get_password_hash("admin123"),
                full_name="Administrator",
                role=UserRole.ADMIN,
                is_active=True
            )
            db.add(admin)
            db.commit()
            logger.info("Default admin user created: admin / admin123")
        else:
            logger.info("Admin user already exists")
    except Exception as e:
        logger.error(f"Error creating admin user: {e}")
    finally:
        db.close()

# Create FastAPI app
app = FastAPI(
    title=settings.APP_NAME,
    version=settings.VERSION,
    description="RR Locker - Mobile Device Management System for EMI-based device financing",
    docs_url="/docs",
    redoc_url="/redoc"
)

# CORS Middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include API router
app.include_router(api_router)


def _keep_alive_worker():
    """Ping own health endpoint every 12 minutes to keep the app warm.
    Samsung provisioning downloads fail when server takes 30-60s to wake up."""
    import time
    import urllib.request
    
    external_url = os.getenv("APP_EXTERNAL_URL", "")
    if not external_url:
        logger.info("APP_EXTERNAL_URL not set — keep-alive disabled")
        return
    
    health_url = f"{external_url}/health"
    logger.info(f"Keep-alive started: pinging {health_url} every 12 minutes")
    time.sleep(120)  # Wait 2 min after startup before first ping
    
    while True:
        try:
            req = urllib.request.Request(health_url, method="GET")
            with urllib.request.urlopen(req, timeout=15) as resp:
                resp.read()
        except Exception as e:
            logger.debug(f"Keep-alive ping failed: {e}")
        time.sleep(720)  # 12 minutes


def _start_keep_alive():
    """Start the keep-alive background thread (daemon, won't block shutdown)."""
    t = threading.Thread(target=_keep_alive_worker, daemon=True, name="keep-alive")
    t.start()


@app.on_event("startup")
async def startup_event():
    """Initialize services on startup"""
    logger.info("Starting RR Locker API...")
    
    # Create database tables
    try:
        create_tables()
        logger.info("Database tables created/verified")
        
        # Migrate: add new columns to existing tables if missing
        migrate_database()
    except Exception as e:
        logger.error(f"Database initialization error: {e}")
    
    # Create default admin user
    try:
        create_default_admin()
    except Exception as e:
        logger.error(f"Error creating default admin: {e}")
    
    # Initialize Firebase
    try:
        init_firebase()
        logger.info("Firebase initialized")
    except Exception as e:
        logger.warning(f"Firebase initialization warning: {e}")
    
    # Start keep-alive pinger to prevent Render free tier cold starts
    # Samsung provisioning fails when server takes too long to wake up
    _start_keep_alive()
    
    logger.info("RR Locker API started successfully")


@app.on_event("shutdown")
async def shutdown_event():
    """Cleanup on shutdown"""
    logger.info("Shutting down RR Locker API...")


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy"}


# ── Serve Dashboard (SPA) ──────────────────────────────────
# The dashboard is built with Vite and placed in backend/static/
# This must come AFTER all API routes so /api/* is handled first
DASHBOARD_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "static")

if os.path.isdir(DASHBOARD_DIR):
    # Serve static assets (JS, CSS, images)
    app.mount("/assets", StaticFiles(directory=os.path.join(DASHBOARD_DIR, "assets")), name="static-assets")

    # Root serves dashboard
    @app.get("/")
    async def serve_root():
        return FileResponse(os.path.join(DASHBOARD_DIR, "index.html"))

    # Catch-all: any path not matching /api, /docs, /health, /assets → SPA
    @app.get("/{full_path:path}")
    async def serve_dashboard(request: Request, full_path: str = ""):
        # If it's a static file that exists, serve it
        file_path = os.path.join(DASHBOARD_DIR, full_path)
        if os.path.isfile(file_path):
            return FileResponse(file_path)
        # Otherwise serve index.html for SPA client-side routing
        return FileResponse(os.path.join(DASHBOARD_DIR, "index.html"))
else:
    logger.warning(f"Dashboard static files not found at {DASHBOARD_DIR}")

    @app.get("/")
    async def root():
        return {
            "app": settings.APP_NAME,
            "version": settings.VERSION,
            "status": "running",
            "docs": "/docs"
        }
