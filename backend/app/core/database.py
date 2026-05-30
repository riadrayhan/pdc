from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
import os
import re
from urllib.parse import quote

from app.core.config import settings


def _sanitize_db_url(url: str) -> str:
    """Percent-encode the username/password in a SQL connection URL so that
    special characters (@ : / # ? etc.) in a Supabase/Neon password don't break
    URL parsing and crash the app at import time."""
    m = re.match(r"^(?P<scheme>[^:]+)://(?P<body>.+)$", url)
    if not m or "@" not in m.group("body"):
        return url
    body = m.group("body")
    # Split on the LAST '@' so a password containing '@' is handled correctly.
    userinfo, host = body.rsplit("@", 1)
    if ":" not in userinfo:
        return url
    user, password = userinfo.split(":", 1)
    safe_user = quote(user, safe="")
    safe_password = quote(password, safe="")
    return f"{m.group('scheme')}://{safe_user}:{safe_password}@{host}"


# Handle different database URL formats
db_url = settings.DATABASE_URL
if db_url.startswith("postgres://"):
    db_url = db_url.replace("postgres://", "postgresql://", 1)

# For SQLite, store the DB file in a persistent directory when one is available.
# Hugging Face Spaces (and many PaaS) expose a writable persistent mount at
# /data. If it exists we put the SQLite file there so it survives container
# rebuilds/redeploys. Otherwise we fall back to the local working directory.
if "sqlite" in db_url:
    # Only relocate the default relative SQLite path, never an explicit absolute one.
    if db_url in ("sqlite:///./emi_locker.db", "sqlite:///emi_locker.db"):
        persistent_dir = "/data"
        if os.path.isdir(persistent_dir) and os.access(persistent_dir, os.W_OK):
            db_url = f"sqlite:///{persistent_dir}/emi_locker.db"
    engine = create_engine(db_url, connect_args={"check_same_thread": False})
else:
    # Postgres / MySQL: encode credentials, then build the engine.
    # pool_pre_ping avoids stale-connection errors on managed DBs (Supabase pooler).
    db_url = _sanitize_db_url(db_url)
    engine = create_engine(db_url, pool_pre_ping=True, pool_recycle=300)


SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def create_tables():
    Base.metadata.create_all(bind=engine)
