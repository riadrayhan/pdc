"""
Zero Touch Enrollment (ZTE) API Routes v2.0

Advanced ZTE management with:
- Database-persisted configuration (survives server restarts)
- ZTE enrollment event tracking & logging
- Statistics & analytics endpoints
- Multi-profile support (different configs for different shops)
- Real-time enrollment progress tracking
- Provisioning history with detailed logs
- Health check & validation
- WiFi credentials passed in admin extras for Device Owner WiFi management
"""
import json
import io
import hashlib
import base64
import os
import logging
import uuid
from datetime import datetime, timedelta
from typing import Optional, List

from fastapi import APIRouter, Request, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session
from sqlalchemy import func, desc

from app.core.database import get_db
from app.core.config import settings
from app.core.security import get_current_user
from app.models.zte import ZTEConfig, ZTEEnrollmentEvent, ZTEProvisioningLog, ZTEEnrollmentStatus

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/zte", tags=["Zero Touch Enrollment"])

# APK file location
APK_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "apk")
APK_FILENAME = "app-release.apk"

# APK hosting ï¿½ Samsung provisioning REQUIRES direct APK serving with HEAD support
# All APK downloads go through backend's own endpoints
# Always use backend's own /zte/apk endpoint for provisioning QR codes
APK_PROVISIONING_URL = "https://riadrayhan111-rr-locker-api.hf.space/api/v1/zte/apk"

APK_MANUAL_DOWNLOAD_URL = "https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download"
# Checksum is computed dynamically from the actual APK file
_cached_checksum = None
_cached_checksum_mtime = None

# Signing certificate checksum (for Samsung / Android 10+)
_cached_sig_checksum = None
_cached_sig_checksum_mtime = None

# Hardcoded fallback checksums ï¿½ used when APK file is not available on server
# These MUST match the current release APK signed with emifinance-release.jks
FALLBACK_SIGNATURE_CHECKSUM = "M3cJdKiSRbG7UPF_EGalAIPWoFlc-86PsVrVtj6jDA4"
FALLBACK_PACKAGE_CHECKSUM = "9pQNHmp25kjdJUjvb9wHIGlFBR3I2p9I2j8QJXlGdmI"

# Samsung requires fully-qualified component name (won't expand shorthand '/.admin.')
DEVICE_ADMIN_COMPONENT = "com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver"


# ================ SCHEMAS ================

class ZTEConfigUpdate(BaseModel):
    wifi_ssid: Optional[str] = Field(None)
    wifi_password: Optional[str] = Field(None)
    wifi_security: Optional[str] = Field(None)
    wifi_hidden: Optional[bool] = Field(None)
    default_lock_message: Optional[str] = Field(None)
    default_contact_number: Optional[str] = Field(None)
    auto_enroll: Optional[bool] = Field(None)
    auto_lock_on_enroll: Optional[bool] = Field(None)
    skip_encryption: Optional[bool] = Field(None)
    leave_all_system_apps: Optional[bool] = Field(None)
    locale: Optional[str] = Field(None)
    time_zone: Optional[str] = Field(None)
    custom_apk_url: Optional[str] = Field(None)
    enabled: Optional[bool] = Field(None)


class ZTEConfigResponse(BaseModel):
    wifi_ssid: str
    wifi_password: str
    wifi_security: str
    wifi_hidden: bool
    default_lock_message: str
    default_contact_number: str
    auto_enroll: bool
    auto_lock_on_enroll: bool
    skip_encryption: bool
    leave_all_system_apps: bool
    locale: str
    time_zone: str
    custom_apk_url: str
    enabled: bool
    apk_download_url: str
    device_admin_component: str


class ZTEEventReport(BaseModel):
    """Report from device during ZTE enrollment progress"""
    imei: Optional[str] = None
    imei2: Optional[str] = None
    serial_number: Optional[str] = None
    persistent_device_id: Optional[str] = None
    android_id: Optional[str] = None
    manufacturer: Optional[str] = None
    model: Optional[str] = None
    android_version: Optional[str] = None
    status: str  # ZTEEnrollmentStatus value
    current_phase: int = 0
    progress_percent: int = 0
    device_id: Optional[str] = None
    fcm_token: Optional[str] = None
    error_message: Optional[str] = None
    failure_phase: Optional[int] = None
    retry_count: int = 0
    network_type: Optional[str] = None
    wifi_ssid: Optional[str] = None
    sim_operator: Optional[str] = None
    sim_country: Optional[str] = None
    phone_number: Optional[str] = None
    server_url: Optional[str] = None
    zte_version: str = "2.0"
    elapsed_seconds: Optional[float] = None
    extra_data: Optional[dict] = None


class ZTEStatsResponse(BaseModel):
    total_enrollments: int
    successful: int
    failed: int
    in_progress: int
    success_rate: float
    avg_enrollment_time_seconds: float
    enrollments_today: int
    enrollments_this_week: int
    enrollments_this_month: int
    recent_events: list
    phase_failure_distribution: dict
    top_manufacturers: list
    top_errors: list


# ================ HELPERS ================

def _get_or_create_config(db: Session) -> ZTEConfig:
    """Get the ZTE config from DB, or create default if none exists."""
    config = db.query(ZTEConfig).filter(ZTEConfig.config_key == "default").first()
    if config is None:
        config = ZTEConfig(
            config_key="default",
            wifi_ssid="",
            wifi_password="",
            wifi_security="WPA",
            wifi_hidden=False,
            default_lock_message="????? EMI ?????? ???? ??????? ??? ?????? ?????",
            default_contact_number="",
            auto_enroll=True,
            auto_lock_on_enroll=False,
            skip_encryption=True,
            leave_all_system_apps=True,
            locale="bn_BD",
            time_zone="Asia/Dhaka",
            custom_apk_url="",
            enabled=True,
        )
        db.add(config)
        db.commit()
        db.refresh(config)
    return config


def _compute_apk_checksum():
    """Compute SHA-256 checksum of APK file for provisioning verification.
    Uses file mtime-based caching to avoid recomputing on every request."""
    global _cached_checksum, _cached_checksum_mtime
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    if not os.path.isfile(apk_path):
        # Try alternate names
        for name in ["app-release.apk", "app-debug.apk", "emi_locker.apk"]:
            alt = os.path.join(APK_DIR, name)
            if os.path.isfile(alt):
                apk_path = alt
                break
        else:
            return None
    mtime = os.path.getmtime(apk_path)
    if _cached_checksum and _cached_checksum_mtime == mtime:
        return _cached_checksum
    sha256 = hashlib.sha256()
    with open(apk_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha256.update(chunk)
    _cached_checksum = base64.urlsafe_b64encode(sha256.digest()).decode("utf-8").rstrip("=")
    _cached_checksum_mtime = mtime
    logger.info(f"APK checksum computed: {_cached_checksum} from {apk_path}")
    return _cached_checksum


def _compute_apk_signature_checksum():
    """Compute SHA-256 checksum of the APK signing certificate.
    
    Samsung devices (and Android 10+ in general) require
    PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM instead of
    PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM.
    
    Supports both:
    - APK Signature Scheme v2/v3 (signing block before Central Directory)
    - Legacy v1 JAR signing (META-INF/*.RSA/.DSA/.EC)
    """
    global _cached_sig_checksum, _cached_sig_checksum_mtime
    import struct
    import zipfile
    
    apk_path = _find_apk_path()
    if not apk_path:
        return None
    
    mtime = os.path.getmtime(apk_path)
    if _cached_sig_checksum and _cached_sig_checksum_mtime == mtime:
        return _cached_sig_checksum
    
    try:
        cert_der = _extract_cert_from_apk_signing_block(apk_path)
        
        if cert_der is None:
            # Fallback: try v1 JAR signature (META-INF/*.RSA)
            cert_der = _extract_cert_from_v1_signature(apk_path)
        
        if cert_der is None:
            logger.warning("No signing certificate found in APK (neither v2/v3 nor v1)")
            return None
        
        sha256 = hashlib.sha256(cert_der)
        _cached_sig_checksum = base64.urlsafe_b64encode(sha256.digest()).decode("utf-8").rstrip("=")
        _cached_sig_checksum_mtime = mtime
        logger.info(f"APK signature checksum computed: {_cached_sig_checksum} (cert {len(cert_der)} bytes)")
        return _cached_sig_checksum
    except Exception as e:
        logger.error(f"Failed to compute APK signature checksum: {e}")
        return None


def _extract_cert_from_apk_signing_block(apk_path):
    """Extract signing certificate from APK Signature Scheme v2/v3 signing block.
    
    The APK Signing Block sits between the last ZIP entry and the Central Directory.
    It ends with the 16-byte magic 'APK Sig Block 42' right before the Central Directory.
    Inside, we find signer blocks with ID 0x7109871a (v2) or 0xf05368c0 (v3)
    that contain the DER-encoded X.509 signing certificate.
    """
    import struct
    
    try:
        with open(apk_path, 'rb') as f:
            data = f.read()
        
        # Step 1: Find ZIP End of Central Directory (EOCD)
        eocd_offset = -1
        for i in range(len(data) - 22, max(len(data) - 65536 - 22, -1), -1):
            if data[i:i+4] == b'\x50\x4b\x05\x06':
                eocd_offset = i
                break
        if eocd_offset < 0:
            return None
        
        # Step 2: Get Central Directory offset from EOCD
        cd_offset = struct.unpack_from('<I', data, eocd_offset + 16)[0]
        if cd_offset < 24:
            return None
        
        # Step 3: Check for APK Signing Block magic before Central Directory
        magic = data[cd_offset - 16 : cd_offset]
        if magic != b'APK Sig Block 42':
            return None
        
        # Step 4: Read block size and find block start
        block_size = struct.unpack_from('<Q', data, cd_offset - 24)[0]
        block_start = cd_offset - block_size - 8
        if block_start < 0:
            return None
        
        # Step 5: Parse signing block ID-value pairs
        pairs_start = block_start + 8
        pairs_end = cd_offset - 24
        
        offset = pairs_start
        while offset < pairs_end:
            pair_size = struct.unpack_from('<Q', data, offset)[0]
            offset += 8
            pair_id = struct.unpack_from('<I', data, offset)[0]
            pair_data = data[offset + 4 : offset + int(pair_size)]
            
            # v2 (0x7109871a) or v3 (0xf05368c0) signing scheme
            if pair_id in (0x7109871a, 0xf05368c0):
                cert = _parse_signer_block_cert(pair_data)
                if cert:
                    return cert
            
            offset += int(pair_size)
        
        return None
    except Exception as e:
        logger.debug(f"APK signing block parsing failed: {e}")
        return None


def _parse_signer_block_cert(data):
    """Parse a v2/v3 signer block to extract the first X.509 certificate.
    
    Structure: length-prefixed signers sequence > signer > signed data >
    (digests, certificates, ...) > first certificate DER bytes.
    """
    import struct
    try:
        off = 0
        # signers sequence (length-prefixed)
        signers_size = struct.unpack_from('<I', data, off)[0]
        off += 4
        # first signer (length-prefixed)
        off += 4  # skip signer_size
        # signed data (length-prefixed)
        signed_data_size = struct.unpack_from('<I', data, off)[0]
        off += 4
        signed_data = data[off : off + signed_data_size]
        
        # Inside signed data: digests (skip), certificates, additional attrs
        inner = 0
        digests_size = struct.unpack_from('<I', signed_data, inner)[0]
        inner += 4 + digests_size
        # certificates sequence
        inner += 4  # skip certs_size
        # first certificate (length-prefixed)
        cert_size = struct.unpack_from('<I', signed_data, inner)[0]
        inner += 4
        return signed_data[inner : inner + cert_size]
    except Exception as e:
        logger.debug(f"Signer block parsing failed: {e}")
        return None


def _extract_cert_from_v1_signature(apk_path):
    """Extract signing certificate from v1 JAR signature (META-INF/*.RSA) using cryptography library.
    
    This is the RELIABLE method â€” uses Python's cryptography library for proper
    PKCS#7/X.509 parsing instead of hand-rolled ASN.1 which can fail on edge cases.
    """
    import zipfile
    try:
        from cryptography.hazmat.primitives.serialization import pkcs7
        from cryptography.hazmat.primitives.serialization import Encoding
    except ImportError:
        logger.warning("cryptography library not available, falling back to manual PKCS#7 parser")
        return _extract_cert_from_v1_signature_manual(apk_path)
    
    try:
        with zipfile.ZipFile(apk_path, 'r') as zf:
            for name in zf.namelist():
                upper = name.upper()
                if upper.startswith('META-INF/') and (
                    upper.endswith('.RSA') or upper.endswith('.DSA') or upper.endswith('.EC')
                ):
                    pkcs7_data = zf.read(name)
                    try:
                        # Use cryptography library for proper PKCS#7 parsing
                        certs = pkcs7.load_der_pkcs7_certificates(pkcs7_data)
                        if certs:
                            cert_der = certs[0].public_bytes(Encoding.DER)
                            logger.info(f"v1 cert extracted via cryptography lib: {len(cert_der)} bytes from {name}")
                            return cert_der
                    except Exception as e:
                        logger.debug(f"cryptography lib PKCS#7 parse failed for {name}: {e}")
                        # Try manual fallback
                        cert = _extract_cert_from_pkcs7(pkcs7_data)
                        return cert if cert else pkcs7_data
        return None
    except Exception as e:
        logger.debug(f"v1 signature extraction failed: {e}")
        return None


def _extract_cert_from_v1_signature_manual(apk_path):
    """Manual fallback for v1 cert extraction when cryptography library is unavailable."""
    import zipfile
    try:
        with zipfile.ZipFile(apk_path, 'r') as zf:
            for name in zf.namelist():
                upper = name.upper()
                if upper.startswith('META-INF/') and (
                    upper.endswith('.RSA') or upper.endswith('.DSA') or upper.endswith('.EC')
                ):
                    pkcs7_data = zf.read(name)
                    cert = _extract_cert_from_pkcs7(pkcs7_data)
                    return cert if cert else pkcs7_data
        return None
    except Exception:
        return None


def _extract_cert_from_pkcs7(data: bytes):
    """Extract the first X.509 certificate DER bytes from a PKCS#7 SignedData structure."""
    try:
        def read_tag_length(data, offset):
            if offset >= len(data):
                return None, 0, 0
            tag = data[offset]
            offset += 1
            if offset >= len(data):
                return tag, 0, 1
            length_byte = data[offset]
            offset += 1
            if length_byte < 0x80:
                return tag, length_byte, 2
            num_bytes = length_byte & 0x7F
            if num_bytes == 0 or offset + num_bytes > len(data):
                return tag, 0, 2
            length = int.from_bytes(data[offset:offset + num_bytes], 'big')
            return tag, length, 2 + num_bytes
        
        def find_certificates(data, offset, end):
            certs = []
            while offset < end:
                tag, length, hdr_size = read_tag_length(data, offset)
                if tag is None or length == 0 and hdr_size <= 1:
                    break
                content_start = offset + hdr_size
                content_end = content_start + length
                if content_end > end:
                    break
                if tag == 0xA0:
                    certs.extend(find_certificates(data, content_start, content_end))
                elif tag == 0x30:
                    if content_start < content_end:
                        inner_tag, _, _ = read_tag_length(data, content_start)
                        if inner_tag == 0x30:
                            cert_bytes = data[offset:content_end]
                            if len(cert_bytes) > 100:
                                certs.append(cert_bytes)
                offset = content_end
            return certs
        
        certs = find_certificates(data, 0, len(data))
        return certs[0] if certs else None
    except Exception:
        return None


def _find_apk_path():
    """Find the APK file, trying multiple names."""
    for name in [APK_FILENAME, "app-release.apk", "app-debug.apk", "emi_locker.apk"]:
        path = os.path.join(APK_DIR, name)
        if os.path.isfile(path):
            return path
    return None


def _get_apk_url(config_dict, base_url=None):
    """Get APK download URL for provisioning QR codes.
    
    CRITICAL: Samsung provisioning requires:
    - Direct APK binary download (no HTML pages)
    - HEAD request support (pre-checks file size)
    - Correct Content-Type: application/vnd.android.package-archive
    - No redirect chains
    
    Always use backend's own /zte/apk endpoint for reliability.
    """
    if config_dict.get("custom_apk_url"):
        return config_dict["custom_apk_url"]
    # Use backend's own endpoint ï¿½ guaranteed to serve raw APK with HEAD support
    if base_url:
        return f"{base_url}/api/v1/zte/apk"
    return APK_PROVISIONING_URL


def _build_provisioning_payload(request: Request, config_dict: dict):
    """
    Build Android Enterprise QR code provisioning payload.
    
    IMPORTANT: Keep payload MINIMAL for maximum device compatibility.
    Many Android OEMs (especially Samsung) have strict QR parsers that
    reject unknown/nested fields.
    
    Samsung-specific fixes (One UI 1/2/3/4/5/6):
    - NO PROVISIONING_ADMIN_EXTRAS_BUNDLE (Samsung parser fails on nested JSON)
    - NO locale/timezone (reduces QR size, Samsung ignores these anyway)
    - BOTH PACKAGE_CHECKSUM and SIGNATURE_CHECKSUM are included:
      * PACKAGE_CHECKSUM: REQUIRED for Android 9-11 (API 28-30) per AOSP spec
      * SIGNATURE_CHECKSUM: REQUIRED for Android 12+ (API 31+)
      * Both must be correct ï¿½ Android validates whichever it supports
    
    NOTE: Earlier comment about Samsung failing with both checksums was WRONG.
    The failure was caused by stale PACKAGE_CHECKSUM not matching the served APK.
    When both checksums are computed from the SAME APK file, Samsung accepts both.
    """
    base_url = str(request.base_url).rstrip("/")
    apk_url = _get_apk_url(config_dict, base_url)
    
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": DEVICE_ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        # CRITICAL: ALLOW_OFFLINE prevents Google's online Play Protect cloud verification
        # Without this, Android 13+ sends APK hash to Google servers for scanning
        # which blocks non-whitelisted MDM apps with "App blocked to protect your device"
        "android.app.extra.PROVISIONING_ALLOW_OFFLINE": True,
        # Minimum version code triggers trusted DPC install path
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 2,
    }
    
    # SIGNATURE_CHECKSUM (SHA-256 of signing certificate) - PRIMARY trust mechanism
    # This is what Android 12+ uses to verify the DPC is trusted during provisioning
    # If this matches, Android SKIPS Play Protect verification for the DPC install
    sig_checksum = _compute_apk_signature_checksum()
    if sig_checksum:
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum
    else:
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = FALLBACK_SIGNATURE_CHECKSUM
    
    # PACKAGE_CHECKSUM (SHA-256 of APK file) - needed for Android 9-11 only
    # Android 12+ ignores this and uses only SIGNATURE_CHECKSUM
    pkg_checksum = _compute_apk_checksum()
    if pkg_checksum:
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = pkg_checksum
    else:
        payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = FALLBACK_PACKAGE_CHECKSUM
    
    # WiFi config (only if SSID is set) ï¿½ essential for devices without SIM data
    wifi_ssid = config_dict.get("wifi_ssid", "")
    if wifi_ssid:
        payload["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi_ssid
        wifi_security = config_dict.get("wifi_security", "WPA")
        if wifi_security and wifi_security != "NONE":
            payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = wifi_security
            wifi_pass = config_dict.get("wifi_password", "")
            if wifi_pass:
                payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifi_pass
        if config_dict.get("wifi_hidden", False):
            payload["android.app.extra.PROVISIONING_WIFI_HIDDEN"] = True
    
    # NOTE: PROVISIONING_ADMIN_EXTRAS_BUNDLE intentionally REMOVED for Samsung compat.
    # Samsung's QR parser on One UI 3/4/5/6 fails on nested JSON objects.
    # The app auto-detects server URL from BuildConfig and auto-enrolls without admin extras.
    
    return payload


def _build_offline_provisioning_payload(local_ip: str, local_port: int = 8080):
    """
    Build a QR provisioning payload for OFFLINE mode.
    
    This is the KEY solution for Android 13+ Play Protect blocking:
    - APK is served from a LOCAL HTTP server (PC on same WiFi, no internet)
    - Device connects to WiFi hotspot that has NO internet access
    - Since there's no internet, Play Protect cloud check CANNOT run
    - Android falls back to local SIGNATURE_CHECKSUM verification only
    - Our checksum matches â†’ APK installs successfully!
    """
    apk_url = f"http://{local_ip}:{local_port}/app-release.apk"
    
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": DEVICE_ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ALLOW_OFFLINE": True,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 2,
    }
    
    # Use checksums from server computation or fallback
    sig_checksum = _compute_apk_signature_checksum() or FALLBACK_SIGNATURE_CHECKSUM
    pkg_checksum = _compute_apk_checksum() or FALLBACK_PACKAGE_CHECKSUM
    
    payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum
    payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = pkg_checksum
    
    return payload


# ================ ENDPOINTS ================

@router.api_route("/apk", methods=["GET", "HEAD"])
async def serve_apk(request: Request):
    """Serve the APK file directly ï¿½ no redirects, reliable for QR provisioning.
    Samsung devices require:
    - HEAD support (pre-checks file size before download)
    - Range requests (for resume on interrupted downloads)
    - Direct download without redirect chains
    """
    from fastapi.responses import Response
    import time as _time
    apk_path = _find_apk_path()
    if not apk_path:
        raise HTTPException(status_code=404, detail="APK file not found on server")
    filename = os.path.basename(apk_path)
    file_size = os.path.getsize(apk_path)
    mtime = os.path.getmtime(apk_path)
    etag = f'"{hashlib.md5(f"{filename}-{file_size}-{mtime}".encode()).hexdigest()}"'
    last_modified = _time.strftime("%a, %d %b %Y %H:%M:%S GMT", _time.gmtime(mtime))

    common_headers = {
        "Content-Disposition": f"attachment; filename={filename}",
        "Accept-Ranges": "bytes",
        "Connection": "keep-alive",
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "public, max-age=3600",
        "ETag": etag,
        "Last-Modified": last_modified,
    }

    # HEAD request ï¿½ Samsung checks file size before downloading
    if request.method == "HEAD":
        return Response(
            content=b"",
            media_type="application/vnd.android.package-archive",
            headers={
                **common_headers,
                "Content-Length": str(file_size),
            },
        )

    # Handle Range requests ï¿½ Samsung resumes interrupted downloads
    range_header = request.headers.get("range")
    if range_header:
        try:
            range_spec = range_header.strip().lower()
            if range_spec.startswith("bytes="):
                range_val = range_spec[6:]
                parts = range_val.split("-")
                start = int(parts[0]) if parts[0] else 0
                end = int(parts[1]) if parts[1] else file_size - 1
                end = min(end, file_size - 1)
                if start >= file_size:
                    return Response(
                        status_code=416,
                        headers={"Content-Range": f"bytes */{file_size}"},
                    )
                content_length = end - start + 1
                with open(apk_path, "rb") as f:
                    f.seek(start)
                    data = f.read(content_length)
                return Response(
                    content=data,
                    status_code=206,
                    media_type="application/vnd.android.package-archive",
                    headers={
                        **common_headers,
                        "Content-Length": str(content_length),
                        "Content-Range": f"bytes {start}-{end}/{file_size}",
                    },
                )
        except (ValueError, IndexError):
            pass  # Fall through to full download

    # Full download ï¿½ include Content-Length explicitly (Samsung requires this)
    from fastapi.responses import FileResponse
    common_headers["Content-Length"] = str(file_size)
    logger.info(f"Serving APK: {filename} ({file_size} bytes)")
    return FileResponse(
        path=apk_path,
        filename=filename,
        media_type="application/vnd.android.package-archive",
        headers=common_headers,
    )


@router.get("/compute-checksum")
async def compute_checksum():
    """Compute and return both APK file and signing certificate checksums."""
    apk_path = _find_apk_path()
    if not apk_path:
        raise HTTPException(status_code=404, detail="APK file not found")
    checksum = _compute_apk_checksum()
    sig_checksum = _compute_apk_signature_checksum()
    file_size = os.path.getsize(apk_path)
    return {
        "checksum": checksum,
        "signature_checksum": sig_checksum,
        "apk_file": os.path.basename(apk_path),
        "file_size_bytes": file_size,
        "file_size_mb": round(file_size / (1024 * 1024), 2),
        "algorithm": "SHA-256",
        "encoding": "URL-safe Base64 (no padding)",
        "note": "signature_checksum is required for Samsung and Android 10+ devices",
    }


@router.get("/config", response_model=ZTEConfigResponse)
async def get_zte_config(request: Request, db: Session = Depends(get_db)):
    """Get current Zero Touch Enrollment configuration (DB-persisted)."""
    config = _get_or_create_config(db)
    config_dict = config.to_dict()
    base_url = str(request.base_url).rstrip("/")
    return ZTEConfigResponse(
        **config_dict,
        apk_download_url=_get_apk_url(config_dict, base_url),
        device_admin_component=DEVICE_ADMIN_COMPONENT,
    )


@router.put("/config", response_model=ZTEConfigResponse)
async def update_zte_config(request: Request, update: ZTEConfigUpdate, db: Session = Depends(get_db), current_user = Depends(get_current_user)):
    """Update ZTE configuration (PATCH semantics, DB-persisted). Requires authentication."""
    config = _get_or_create_config(db)
    
    update_data = update.dict(exclude_unset=True)
    for key, value in update_data.items():
        if hasattr(config, key):
            setattr(config, key, value)
    
    config.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(config)
    
    logger.info(f"ZTE config updated: {list(update_data.keys())}")
    
    config_dict = config.to_dict()
    base_url = str(request.base_url).rstrip("/")
    return ZTEConfigResponse(
        **config_dict,
        apk_download_url=_get_apk_url(config_dict, base_url),
        device_admin_component=DEVICE_ADMIN_COMPONENT,
    )


@router.get("/provisioning-qr")
async def zte_provisioning_qr(request: Request, db: Session = Depends(get_db)):
    """
    Generate the Zero Touch Enrollment provisioning QR code.
    
    Enhanced v2.0:
    - WiFi credentials embedded in admin extras for Device Owner management
    - Optimized QR data size
    - Error correction level M for balance of size/reliability
    """
    import qrcode
    
    config = _get_or_create_config(db)
    config_dict = config.to_dict()
    
    if not config_dict.get("enabled", True):
        raise HTTPException(status_code=400, detail="Zero Touch Enrollment is disabled")
    
    payload = _build_provisioning_payload(request, config_dict)
    qr_data = json.dumps(payload, separators=(',', ':'))  # Compact JSON
    
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=8,
        border=4,
    )
    qr.add_data(qr_data)
    qr.make(fit=True)
    
    img = qr.make_image(fill_color="#1a1a2e", back_color="white")
    
    img_buffer = io.BytesIO()
    img.save(img_buffer, format="PNG")
    img_buffer.seek(0)
    
    return StreamingResponse(
        img_buffer,
        media_type="image/png",
        headers={
            "Content-Disposition": "inline; filename=zte-provisioning-qr.png",
            "Cache-Control": "no-cache"
        }
    )


@router.get("/provisioning-data")
async def zte_provisioning_data(request: Request, db: Session = Depends(get_db)):
    """Return the ZTE provisioning QR data as JSON for client-side QR generation."""
    config = _get_or_create_config(db)
    config_dict = config.to_dict()
    
    if not config_dict.get("enabled", True):
        raise HTTPException(status_code=400, detail="Zero Touch Enrollment is disabled")
    
    payload = _build_provisioning_payload(request, config_dict)
    base_url = str(request.base_url).rstrip("/")
    
    return {
        "provisioning_data": payload,
        "qr_string": json.dumps(payload, separators=(',', ':')),
        "zte_config": config_dict,
        "device_admin_component": DEVICE_ADMIN_COMPONENT,
        "apk_download_url": _get_apk_url(config_dict, base_url),
        "apk_checksum": _compute_apk_checksum() or FALLBACK_PACKAGE_CHECKSUM,
        "apk_signature_checksum": _compute_apk_signature_checksum() or FALLBACK_SIGNATURE_CHECKSUM,
        "zte_version": "2.0",
        "instructions": {
            "en": [
                "Factory reset the phone (or use brand new phone)",
                "At the welcome screen, tap 6 times quickly on the text",
                "WiFi will auto-connect (if configured) or connect manually",
                "Scan this QR code",
                "Phone automatically downloads, installs, and configures everything",
                "Device enrolls to server automatically ï¿½ ZERO manual steps!",
            ],
            "bn": [
                "??? Factory Reset ???? (???? ???? ??? ??????? ????)",
                "Welcome screen ? 6 ??? ????? tap ????",
                "WiFi ???????????????? connect ??? (??? configure ??? ????) ???? manually connect ????",
                "?? QR code scan ????",
                "??? automatically ?? download, install ??? configure ????",
                "?????? ???????? ???? ???? enroll ??? ï¿½ ???? manual step ???!",
            ]
        }
    }


@router.get("/debug-checksums")
async def debug_checksums():
    """Debug endpoint: verify APK checksums for provisioning troubleshooting.
    
    If Play Protect blocks installation during QR provisioning, it's usually because
    the SIGNATURE_CHECKSUM in the QR doesn't match the actual APK signing certificate.
    Use this endpoint to verify checksums are correct.
    """
    apk_path = _find_apk_path()
    
    # Compute checksums with detailed method info
    pkg_checksum = _compute_apk_checksum()
    sig_checksum = _compute_apk_signature_checksum()
    
    # Try to verify v1 cert using cryptography library independently
    v1_cert_info = None
    v2v3_cert_info = None
    if apk_path:
        try:
            v2v3_cert = _extract_cert_from_apk_signing_block(apk_path)
            if v2v3_cert:
                v2v3_hash = base64.urlsafe_b64encode(hashlib.sha256(v2v3_cert).digest()).decode("utf-8").rstrip("=")
                v2v3_cert_info = {"found": True, "cert_size": len(v2v3_cert), "checksum": v2v3_hash}
            else:
                v2v3_cert_info = {"found": False}
        except Exception as e:
            v2v3_cert_info = {"found": False, "error": str(e)}
        
        try:
            v1_cert = _extract_cert_from_v1_signature(apk_path)
            if v1_cert:
                v1_hash = base64.urlsafe_b64encode(hashlib.sha256(v1_cert).digest()).decode("utf-8").rstrip("=")
                v1_cert_info = {"found": True, "cert_size": len(v1_cert), "checksum": v1_hash}
            else:
                v1_cert_info = {"found": False}
        except Exception as e:
            v1_cert_info = {"found": False, "error": str(e)}
    
    result = {
        "apk_found": apk_path is not None,
        "apk_path": apk_path,
        "apk_size": os.path.getsize(apk_path) if apk_path else None,
        "package_checksum": pkg_checksum,
        "signature_checksum": sig_checksum,
        "fallback_package_checksum": FALLBACK_PACKAGE_CHECKSUM,
        "fallback_signature_checksum": FALLBACK_SIGNATURE_CHECKSUM,
        "using_fallback_pkg": pkg_checksum is None,
        "using_fallback_sig": sig_checksum is None,
        "v2v3_cert": v2v3_cert_info,
        "v1_cert": v1_cert_info,
        "checksums_match_fallback": {
            "package": pkg_checksum == FALLBACK_PACKAGE_CHECKSUM if pkg_checksum else "N/A (using fallback)",
            "signature": sig_checksum == FALLBACK_SIGNATURE_CHECKSUM if sig_checksum else "N/A (using fallback)",
        },
        "v1_v2v3_match": (
            v2v3_cert_info.get("checksum") == v1_cert_info.get("checksum")
            if v2v3_cert_info and v1_cert_info and v2v3_cert_info.get("found") and v1_cert_info.get("found")
            else "N/A"
        ),
        "troubleshooting": [
            "If 'apk_found' is false: APK not on server, using fallback checksums which may be stale",
            "If v1 and v2v3 checksums don't match: signing cert extraction is buggy â€” report this",
            "If Play Protect blocks: the signature_checksum may not match what Android computes",
            "After deploying new APK: hit this endpoint to verify checksums auto-updated",
        ]
    }
    return result


@router.post("/test-config")
async def test_zte_config(request: Request, db: Session = Depends(get_db)):
    """Test current ZTE configuration ï¿½ returns validation results."""
    config = _get_or_create_config(db)
    config_dict = config.to_dict()
    warnings = []
    
    if not config_dict.get("enabled"):
        warnings.append("ZTE is currently DISABLED")
    
    if not config_dict.get("wifi_ssid"):
        warnings.append("No WiFi configured ï¿½ device will need manual WiFi connection during setup")
    
    if not config_dict.get("default_contact_number"):
        warnings.append("No contact number set ï¿½ lock screen won't show contact info")
    
    apk_url = _get_apk_url(config_dict, str(request.base_url).rstrip("/"))
    
    # Check APK file availability
    apk_path = _find_apk_path()
    if not apk_path:
        warnings.append("APK file not found on server ï¿½ upload APK to backend/apk/ directory")
    
    # Check checksum
    checksum = _compute_apk_checksum()
    if not checksum:
        warnings.append("Cannot compute APK checksum ï¿½ provisioning will fail on Android < 10")
    
    # Check signature checksum (critical for Samsung)
    sig_checksum = _compute_apk_signature_checksum()
    if not sig_checksum:
        warnings.append("Cannot compute APK signature checksum ï¿½ Samsung devices will FAIL to download APK")
    
    payload = _build_provisioning_payload(request, config_dict)
    qr_string = json.dumps(payload, separators=(',', ':'))
    
    return {
        "valid": len(warnings) == 0 or (len(warnings) == 1 and "No WiFi" in warnings[0]),
        "warnings": warnings,
        "qr_data_size_bytes": len(qr_string.encode("utf-8")),
        "qr_data_size_chars": len(qr_string),
        "max_recommended_chars": 4296,
        "fits_in_qr": len(qr_string) <= 4296,
        "zte_version": "2.0",
        "provisioning_payload": payload,
        "qr_string": qr_string,
    }


@router.get("/offline-qr-data")
async def get_offline_qr_data(
    ip: str = Query(..., description="Local PC IP address (e.g. 192.168.43.1)"),
    port: int = Query(8080, description="Local HTTP server port"),
):
    """
    Generate QR provisioning payload for OFFLINE local provisioning.
    
    This bypasses Android 13+ Play Protect cloud verification by using
    a local WiFi network without internet access.
    
    Setup:
    1. PC creates WiFi hotspot (mobile hotspot, no internet)
    2. PC runs local HTTP server with the APK
    3. This endpoint generates QR code pointing to the PC's local IP
    4. Device scans QR â†’ downloads from PC â†’ no internet â†’ no Play Protect block
    """
    payload = _build_offline_provisioning_payload(ip, port)
    qr_string = json.dumps(payload, separators=(',', ':'))
    
    return {
        "provisioning_payload": payload,
        "qr_string": qr_string,
        "local_apk_url": f"http://{ip}:{port}/app-release.apk",
        "instructions": {
            "step1": "PC à¦¤à§‡ WiFi Hotspot à¦šà¦¾à¦²à§ à¦•à¦°à§à¦¨ (Mobile Data OFF à¦°à¦¾à¦–à§à¦¨)",
            "step2": "PC à¦¤à§‡ local_server.bat à¦šà¦¾à¦²à¦¾à¦¨ (APK à¦¸à¦¾à¦°à§à¦­ à¦•à¦°à¦¬à§‡)",
            "step3": "à¦«à§‹à¦¨ Factory Reset à¦•à¦°à§à¦¨",
            "step4": "à¦«à§‹à¦¨ PC à¦à¦° WiFi Hotspot à¦ Connect à¦•à¦°à§à¦¨",
            "step5": "Welcome Screen â†’ 6x Tap â†’ QR Scan à¦•à¦°à§à¦¨",
            "step6": "Device Owner à¦¸à§‡à¦Ÿà¦†à¦ª à¦¹à¦¬à§‡ Play Protect block à¦›à¦¾à¦¡à¦¼à¦¾à¦‡!",
        }
    }


# ================ ZTE EVENT TRACKING ================

@router.post("/report-progress")
async def report_zte_progress(report: ZTEEventReport, db: Session = Depends(get_db)):
    """
    Receive ZTE enrollment progress reports from devices.
    Creates or updates enrollment event records.
    """
    # Find existing event by device identifiers
    event = None
    if report.persistent_device_id:
        event = db.query(ZTEEnrollmentEvent).filter(
            ZTEEnrollmentEvent.persistent_device_id == report.persistent_device_id,
            ZTEEnrollmentEvent.status != ZTEEnrollmentStatus.COMPLETED,
            ZTEEnrollmentEvent.status != ZTEEnrollmentStatus.FAILED,
        ).first()
    
    if event is None and report.imei:
        event = db.query(ZTEEnrollmentEvent).filter(
            ZTEEnrollmentEvent.imei == report.imei,
            ZTEEnrollmentEvent.status != ZTEEnrollmentStatus.COMPLETED,
            ZTEEnrollmentEvent.status != ZTEEnrollmentStatus.FAILED,
        ).first()
    
    if event is None:
        # Create new event
        event = ZTEEnrollmentEvent(
            imei=report.imei,
            imei2=report.imei2,
            serial_number=report.serial_number,
            persistent_device_id=report.persistent_device_id,
            android_id=report.android_id,
            manufacturer=report.manufacturer,
            model=report.model,
            android_version=report.android_version,
            server_url=report.server_url,
            zte_version=report.zte_version,
        )
        db.add(event)
    
    # Update event
    try:
        event.status = ZTEEnrollmentStatus(report.status)
    except ValueError:
        event.status = ZTEEnrollmentStatus.PROVISIONED
    
    event.current_phase = report.current_phase
    event.progress_percent = report.progress_percent
    event.retry_count = report.retry_count
    event.network_type = report.network_type
    event.wifi_ssid = report.wifi_ssid
    event.sim_operator = report.sim_operator
    event.sim_country = report.sim_country
    event.phone_number = report.phone_number
    event.extra_data = report.extra_data
    
    if report.device_id:
        event.device_id = report.device_id
    if report.fcm_token:
        event.fcm_token = report.fcm_token
    if report.error_message:
        event.last_error = report.error_message
    if report.failure_phase is not None:
        event.failure_phase = report.failure_phase
    if report.elapsed_seconds:
        event.elapsed_seconds = report.elapsed_seconds
    
    if report.status == "completed":
        event.completed_at = datetime.utcnow()
    
    event.updated_at = datetime.utcnow()
    
    # Create log entry for this phase
    log = ZTEProvisioningLog(
        event_id=event.id,
        phase=report.current_phase,
        phase_name=_get_phase_name(report.current_phase),
        status="completed" if report.status != "failed" else "failed",
        message=report.error_message,
    )
    db.add(log)
    
    db.commit()
    
    logger.info(f"ZTE progress: {report.imei or report.persistent_device_id} "
                f"phase={report.current_phase} status={report.status}")
    
    return {"success": True, "event_id": str(event.id)}


@router.get("/stats")
async def get_zte_stats(
    days: int = Query(30, ge=1, le=365),
    db: Session = Depends(get_db),
    current_user = Depends(get_current_user)
):
    """
    Get comprehensive ZTE enrollment statistics.
    """
    since = datetime.utcnow() - timedelta(days=days)
    today = datetime.utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
    week_ago = today - timedelta(days=7)
    month_ago = today - timedelta(days=30)
    
    # Base query for time period
    base_q = db.query(ZTEEnrollmentEvent).filter(ZTEEnrollmentEvent.created_at >= since)
    
    total = base_q.count()
    successful = base_q.filter(ZTEEnrollmentEvent.status == ZTEEnrollmentStatus.COMPLETED).count()
    failed = base_q.filter(ZTEEnrollmentEvent.status == ZTEEnrollmentStatus.FAILED).count()
    in_progress = base_q.filter(
        ZTEEnrollmentEvent.status.notin_([
            ZTEEnrollmentStatus.COMPLETED, ZTEEnrollmentStatus.FAILED
        ])
    ).count()
    
    # Average enrollment time
    avg_time_result = db.query(func.avg(ZTEEnrollmentEvent.elapsed_seconds)).filter(
        ZTEEnrollmentEvent.status == ZTEEnrollmentStatus.COMPLETED,
        ZTEEnrollmentEvent.elapsed_seconds.isnot(None),
    ).scalar()
    avg_time = float(avg_time_result) if avg_time_result else 0.0
    
    # Time-based counts
    enrollments_today = db.query(ZTEEnrollmentEvent).filter(
        ZTEEnrollmentEvent.created_at >= today).count()
    enrollments_week = db.query(ZTEEnrollmentEvent).filter(
        ZTEEnrollmentEvent.created_at >= week_ago).count()
    enrollments_month = db.query(ZTEEnrollmentEvent).filter(
        ZTEEnrollmentEvent.created_at >= month_ago).count()
    
    # Recent events
    recent = db.query(ZTEEnrollmentEvent).order_by(
        desc(ZTEEnrollmentEvent.updated_at)
    ).limit(20).all()
    
    recent_list = [{
        "id": str(e.id),
        "imei": e.imei,
        "manufacturer": e.manufacturer,
        "model": e.model,
        "status": e.status.value if e.status else "unknown",
        "current_phase": e.current_phase,
        "progress_percent": e.progress_percent,
        "elapsed_seconds": e.elapsed_seconds,
        "retry_count": e.retry_count,
        "last_error": e.last_error,
        "sim_operator": e.sim_operator,
        "started_at": e.started_at.isoformat() if e.started_at else None,
        "completed_at": e.completed_at.isoformat() if e.completed_at else None,
        "device_id": e.device_id,
    } for e in recent]
    
    # Phase failure distribution
    failed_events = db.query(
        ZTEEnrollmentEvent.failure_phase,
        func.count(ZTEEnrollmentEvent.id)
    ).filter(
        ZTEEnrollmentEvent.status == ZTEEnrollmentStatus.FAILED,
        ZTEEnrollmentEvent.failure_phase.isnot(None),
    ).group_by(ZTEEnrollmentEvent.failure_phase).all()
    
    phase_failures = {
        _get_phase_name(phase): count 
        for phase, count in failed_events
    }
    
    # Top manufacturers
    top_mfr = db.query(
        ZTEEnrollmentEvent.manufacturer,
        func.count(ZTEEnrollmentEvent.id)
    ).filter(
        ZTEEnrollmentEvent.manufacturer.isnot(None)
    ).group_by(ZTEEnrollmentEvent.manufacturer).order_by(
        desc(func.count(ZTEEnrollmentEvent.id))
    ).limit(10).all()
    
    # Top errors
    top_errors = db.query(
        ZTEEnrollmentEvent.last_error,
        func.count(ZTEEnrollmentEvent.id)
    ).filter(
        ZTEEnrollmentEvent.last_error.isnot(None),
        ZTEEnrollmentEvent.status == ZTEEnrollmentStatus.FAILED,
    ).group_by(ZTEEnrollmentEvent.last_error).order_by(
        desc(func.count(ZTEEnrollmentEvent.id))
    ).limit(5).all()
    
    return ZTEStatsResponse(
        total_enrollments=total,
        successful=successful,
        failed=failed,
        in_progress=in_progress,
        success_rate=round((successful / total * 100) if total > 0 else 0.0, 1),
        avg_enrollment_time_seconds=round(avg_time, 1),
        enrollments_today=enrollments_today,
        enrollments_this_week=enrollments_week,
        enrollments_this_month=enrollments_month,
        recent_events=recent_list,
        phase_failure_distribution=phase_failures,
        top_manufacturers=[{"name": m, "count": c} for m, c in top_mfr],
        top_errors=[{"error": e, "count": c} for e, c in top_errors],
    )


@router.get("/events")
async def list_zte_events(
    status: Optional[str] = None,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: Session = Depends(get_db),
    current_user = Depends(get_current_user)
):
    """List ZTE enrollment events with pagination and filtering."""
    query = db.query(ZTEEnrollmentEvent)
    
    if status:
        try:
            status_enum = ZTEEnrollmentStatus(status)
            query = query.filter(ZTEEnrollmentEvent.status == status_enum)
        except ValueError:
            pass
    
    total = query.count()
    events = query.order_by(desc(ZTEEnrollmentEvent.updated_at)).offset(offset).limit(limit).all()
    
    return {
        "total": total,
        "events": [{
            "id": str(e.id),
            "imei": e.imei,
            "serial_number": e.serial_number,
            "manufacturer": e.manufacturer,
            "model": e.model,
            "android_version": e.android_version,
            "status": e.status.value if e.status else "unknown",
            "current_phase": e.current_phase,
            "total_phases": e.total_phases,
            "progress_percent": e.progress_percent,
            "started_at": e.started_at.isoformat() if e.started_at else None,
            "completed_at": e.completed_at.isoformat() if e.completed_at else None,
            "elapsed_seconds": e.elapsed_seconds,
            "device_id": e.device_id,
            "retry_count": e.retry_count,
            "last_error": e.last_error,
            "failure_phase": e.failure_phase,
            "network_type": e.network_type,
            "sim_operator": e.sim_operator,
            "sim_country": e.sim_country,
            "phone_number": e.phone_number,
            "zte_version": e.zte_version,
        } for e in events],
    }


@router.get("/events/{event_id}")
async def get_zte_event_detail(event_id: str, db: Session = Depends(get_db)):
    """Get detailed ZTE enrollment event with provisioning logs."""
    event = db.query(ZTEEnrollmentEvent).filter(ZTEEnrollmentEvent.id == event_id).first()
    if not event:
        raise HTTPException(status_code=404, detail="ZTE event not found")
    
    logs = db.query(ZTEProvisioningLog).filter(
        ZTEProvisioningLog.event_id == event.id
    ).order_by(ZTEProvisioningLog.timestamp).all()
    
    return {
        "event": {
            "id": str(event.id),
            "imei": event.imei,
            "imei2": event.imei2,
            "serial_number": event.serial_number,
            "persistent_device_id": event.persistent_device_id,
            "manufacturer": event.manufacturer,
            "model": event.model,
            "android_version": event.android_version,
            "status": event.status.value if event.status else "unknown",
            "current_phase": event.current_phase,
            "progress_percent": event.progress_percent,
            "started_at": event.started_at.isoformat() if event.started_at else None,
            "completed_at": event.completed_at.isoformat() if event.completed_at else None,
            "elapsed_seconds": event.elapsed_seconds,
            "device_id": event.device_id,
            "fcm_token": event.fcm_token,
            "retry_count": event.retry_count,
            "last_error": event.last_error,
            "failure_phase": event.failure_phase,
            "network_type": event.network_type,
            "wifi_ssid": event.wifi_ssid,
            "sim_operator": event.sim_operator,
            "sim_country": event.sim_country,
            "phone_number": event.phone_number,
            "server_url": event.server_url,
            "zte_version": event.zte_version,
            "extra_data": event.extra_data,
        },
        "logs": [{
            "phase": log.phase,
            "phase_name": log.phase_name,
            "status": log.status,
            "message": log.message,
            "duration_ms": log.duration_ms,
            "timestamp": log.timestamp.isoformat() if log.timestamp else None,
        } for log in logs],
    }


@router.get("/active-enrollments")
async def get_active_enrollments(db: Session = Depends(get_db), current_user = Depends(get_current_user)):
    """Get currently in-progress ZTE enrollments (real-time monitoring)."""
    active = db.query(ZTEEnrollmentEvent).filter(
        ZTEEnrollmentEvent.status.notin_([
            ZTEEnrollmentStatus.COMPLETED,
            ZTEEnrollmentStatus.FAILED,
        ]),
        ZTEEnrollmentEvent.updated_at >= datetime.utcnow() - timedelta(hours=1),
    ).order_by(desc(ZTEEnrollmentEvent.updated_at)).all()
    
    return {
        "count": len(active),
        "enrollments": [{
            "id": str(e.id),
            "imei": e.imei,
            "manufacturer": e.manufacturer,
            "model": e.model,
            "status": e.status.value if e.status else "unknown",
            "current_phase": e.current_phase,
            "progress_percent": e.progress_percent,
            "started_at": e.started_at.isoformat() if e.started_at else None,
            "elapsed_seconds": (datetime.utcnow() - e.started_at).total_seconds() if e.started_at else 0,
            "retry_count": e.retry_count,
        } for e in active],
    }


# ================ HELPER FUNCTIONS ================

def _get_phase_name(phase: int) -> str:
    """Get human-readable phase name."""
    names = {
        0: "Initializing",
        1: "Granting Permissions",
        2: "Configuring WiFi",
        3: "Battery Optimization",
        4: "Getting FCM Token",
        5: "Collecting Fingerprint",
        6: "Checking Server",
        7: "Enrolling Device",
        8: "Verifying Enrollment",
        9: "Applying Protections",
        10: "Finalizing",
        11: "Complete",
    }
    return names.get(phase, f"Phase {phase}")


# ================ SAMSUNG KNOX KME ENDPOINTS ================

@router.get("/samsung-kme-config")
async def get_samsung_kme_config(request: Request, db: Session = Depends(get_db)):
    """
    Get Samsung Knox Mobile Enrollment (KME) configuration.
    
    This endpoint returns the configuration needed to set up Samsung devices
    via Knox Mobile Enrollment portal (https://samsungknox.com/).
    
    Samsung KME workflow:
    1. Register at Samsung Knox portal
    2. Add device IMEIs to Knox portal
    3. Use this config as MDM profile
    4. When Samsung device is factory reset, Knox auto-downloads and installs the app
    
    This is the Samsung-equivalent of Google Zero-Touch Enrollment.
    KME works on ALL Samsung devices with Knox 2.4+ (Galaxy S6 and later).
    """
    config = _get_or_create_config(db)
    config_dict = config.to_dict()
    base_url = str(request.base_url).rstrip("/")
    apk_url = _get_apk_url(config_dict, base_url)
    sig_checksum = _compute_apk_signature_checksum()
    
    # Samsung KME MDM profile configuration
    kme_config = {
        "mdm": {
            "packageName": "com.riad.rrlkr",
            "downloadUrl": apk_url,
            "signature": sig_checksum or "",
            "deviceAdminComponentName": DEVICE_ADMIN_COMPONENT,
        },
        "settings": {
            "factoryResetProtection": True,
            "allowUnenroll": False,
            "systemAppsEnabled": True,
            "skipEncryption": True,
        },
        "enrollment": {
            "autoEnroll": True,
            "serverUrl": base_url,
            "zteVersion": "2.0",
        },
        "wifi": {},
    }
    
    # Add WiFi config if set
    wifi_ssid = config_dict.get("wifi_ssid", "")
    if wifi_ssid:
        kme_config["wifi"] = {
            "ssid": wifi_ssid,
            "password": config_dict.get("wifi_password", ""),
            "security": config_dict.get("wifi_security", "WPA"),
            "hidden": config_dict.get("wifi_hidden", False),
        }
    
    # Generate Samsung KME QR code data (Samsung-specific format)
    samsung_qr_payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": DEVICE_ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ALLOW_OFFLINE": True,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 2,
    }
    
    if sig_checksum:
        samsung_qr_payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum
    
    if wifi_ssid:
        samsung_qr_payload["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi_ssid
        wifi_security = config_dict.get("wifi_security", "WPA")
        if wifi_security and wifi_security != "NONE":
            samsung_qr_payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = wifi_security
            wifi_pass = config_dict.get("wifi_password", "")
            if wifi_pass:
                samsung_qr_payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifi_pass
    
    return {
        "kme_config": kme_config,
        "samsung_qr_payload": samsung_qr_payload,
        "samsung_qr_string": json.dumps(samsung_qr_payload, separators=(',', ':')),
        "instructions": {
            "en": [
                "Go to Samsung Knox Portal: https://samsungknox.com/",
                "Create an MDM profile with the packageName and downloadUrl above",
                "Add your Samsung device IMEIs to the Knox portal",
                "Assign the MDM profile to the IMEIs",
                "Factory reset the Samsung device",
                "The device will auto-download and install the app via Knox",
                "",
                "Alternative (QR Code): Factory reset ? Welcome screen ? tap 6 times ? scan Samsung QR",
            ],
            "bn": [
                "Samsung Knox Portal ? ???: https://samsungknox.com/",
                "????? packageName ??? downloadUrl ????? MDM profile ???? ????",
                "Samsung device ?? IMEI Knox portal ? add ????",
                "MDM profile IMEI ?????? assign ????",
                "Samsung device Factory Reset ????",
                "Knox ???????????????? app download ? install ????",
                "",
                "?????? (QR Code): Factory Reset ? Welcome screen ? 6 ??? tap ? Samsung QR scan ????",
            ],
        },
        "compatibility": {
            "minimum_knox_version": "2.4",
            "minimum_android_version": "6.0",
            "supported_samsung_series": [
                "Galaxy S6 and later",
                "Galaxy Note 5 and later",
                "Galaxy A series (2016 and later)",
                "Galaxy J series (2016 and later)",
                "Galaxy M series (all)",
                "Galaxy F series (all)",
            ],
            "android_13_compatible": True,
            "android_14_compatible": True,
            "one_ui_5_compatible": True,
            "one_ui_6_compatible": True,
        },
    }


@router.get("/device-compatibility")
async def check_device_compatibility():
    """
    Return device compatibility information for ZTE provisioning.
    Helps admin know which devices support QR provisioning.
    """
    return {
        "qr_provisioning": {
            "minimum_android": "7.0 (API 24)",
            "recommended_android": "10+ (API 29+)",
            "samsung": {
                "compatible": True,
                "minimum_one_ui": "2.0",
                "notes": [
                    "Samsung devices use Knox Mobile Enrollment (KME)",
                    "QR code works on ALL Samsung with Android 7.0+",
                    "Samsung One UI 5+ (Android 13+): WiFi picker shown for APK download",
                    "Samsung One UI 6+ (Android 14+): Fully compatible",
                    "Samsung does NOT support admin extras in QR (nested JSON rejected)",
                    "Samsung requires SIGNATURE_CHECKSUM (not PACKAGE_CHECKSUM)",
                ],
            },
            "google_pixel": {
                "compatible": True,
                "notes": [
                    "Full Android Enterprise support",
                    "Works with both QR code and Google Zero-Touch portal",
                ],
            },
            "xiaomi_redmi_poco": {
                "compatible": True,
                "notes": [
                    "QR provisioning works on MIUI 12+",
                    "Some older MIUI versions need manual setup via ADB",
                    "MIUI may require disabling MIUI optimization in developer options",
                ],
            },
            "oppo_realme_oneplus": {
                "compatible": True,
                "notes": [
                    "ColorOS/RealmeUI/OxygenOS support QR provisioning",
                    "Android 10+ recommended for reliable provisioning",
                ],
            },
            "vivo": {
                "compatible": True,
                "notes": [
                    "FuntouchOS/OriginOS support QR provisioning on Android 9+",
                    "Older Vivo may need ADB setup",
                ],
            },
        },
        "known_issues": [
            "Samsung: Debug-signed APKs are blocked by Play Protect. Use release-signed APK.",
            "Android 13+: testOnly=true APKs are rejected. Ensure testOnly=false in manifest.",
            "Android 14+: Foreground service types must be declared in manifest.",
            "All: APK must be served with Content-Length header (Samsung validates file size).",
            "All: Signature checksum must match the APK on server exactly.",
        ],
    }
