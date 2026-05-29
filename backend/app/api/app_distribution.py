"""
App Distribution API - APK download and QR code generation
"""
from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, StreamingResponse, HTMLResponse, RedirectResponse
import qrcode
import io
import os
import json
import hashlib
import base64
import logging

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/app", tags=["App Distribution"])

# APK file location
APK_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "apk")
APK_FILENAME = "app-release.apk"

# All downloads go through backend's own endpoint
APK_DOWNLOAD_URL = "https://rr-locker-api.onrender.com/api/v1/app/download"
APK_PROVISIONING_URL = "https://rr-locker-api.onrender.com/api/v1/app/download"
# SHA-256 checksum (URL-safe Base64)
APK_CHECKSUM = "9pQNHmp25kjdJUjvb9wHIGlFBR3I2p9I2j8QJXlGdmI"
APK_SIG_CHECKSUM = "M3cJdKiSRbG7UPF_EGalAIPWoFlc-86PsVrVtj6jDA4"

# Correct Device Admin component name
# Samsung requires fully-qualified component name
DEVICE_ADMIN_COMPONENT = "com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver"


@router.api_route("/download", methods=["GET", "HEAD"])
async def download_apk(request: Request):
    """Download APK directly ï¿½ serves from local file first, falls back to redirect.
    Samsung provisioning requires HEAD support and direct download without redirects."""
    from fastapi.responses import Response
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    if os.path.isfile(apk_path):
        file_size = os.path.getsize(apk_path)
        headers = {
            "Content-Disposition": f"attachment; filename={APK_FILENAME}",
            "Accept-Ranges": "bytes",
            "Cache-Control": "public, max-age=3600",
            "Connection": "keep-alive",
        }
        if request.method == "HEAD":
            return Response(
                content=b"",
                media_type="application/vnd.android.package-archive",
                headers={**headers, "Content-Length": str(file_size)},
            )
        return FileResponse(
            path=apk_path,
            filename=APK_FILENAME,
            media_type="application/vnd.android.package-archive",
            headers=headers,
        )
    return RedirectResponse(url=APK_DOWNLOAD_URL, status_code=302)


@router.get("/download-local")
async def download_apk_local():
    """Download the RR Locker APK file from server"""
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    
    if not os.path.isfile(apk_path):
        return {"error": "APK file not found", "path": apk_path}
    
    return FileResponse(
        path=apk_path,
        filename=APK_FILENAME,
        media_type="application/vnd.android.package-archive",
        headers={
            "Content-Disposition": f"attachment; filename={APK_FILENAME}"
        }
    )


@router.get("/install", response_class=HTMLResponse)
async def install_page(request: Request):
    """Auto-download landing page - user scans QR and app auto-downloads with install guide"""
    base_url = str(request.base_url).rstrip("/")
    download_url = f"{base_url}/api/v1/app/download"
    
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>RR Locker - Install</title>
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }}
        .card {{
            background: white;
            border-radius: 24px;
            padding: 32px 24px;
            max-width: 400px;
            width: 100%;
            box-shadow: 0 25px 50px rgba(0,0,0,0.25);
            text-align: center;
        }}
        .logo {{
            width: 80px;
            height: 80px;
            background: linear-gradient(135deg, #667eea, #764ba2);
            border-radius: 20px;
            display: flex;
            align-items: center;
            justify-content: center;
            margin: 0 auto 20px;
            font-size: 36px;
            color: white;
        }}
        h1 {{
            font-size: 24px;
            color: #1a1a2e;
            margin-bottom: 8px;
        }}
        .subtitle {{
            color: #666;
            font-size: 14px;
            margin-bottom: 24px;
        }}
        .download-btn {{
            display: block;
            width: 100%;
            padding: 16px;
            background: linear-gradient(135deg, #667eea, #764ba2);
            color: white;
            border: none;
            border-radius: 14px;
            font-size: 18px;
            font-weight: 600;
            cursor: pointer;
            text-decoration: none;
            margin-bottom: 12px;
            transition: transform 0.2s, box-shadow 0.2s;
        }}
        .download-btn:hover {{
            transform: translateY(-2px);
            box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
        }}
        .download-btn:active {{ transform: translateY(0); }}
        .download-btn .icon {{ font-size: 22px; margin-right: 8px; }}
        .status {{
            padding: 12px 16px;
            border-radius: 12px;
            margin-bottom: 20px;
            font-size: 14px;
            font-weight: 500;
        }}
        .status.downloading {{
            background: #e8f5e9;
            color: #2e7d32;
        }}
        .status.waiting {{
            background: #fff3e0;
            color: #e65100;
        }}
        .progress-bar {{
            width: 100%;
            height: 6px;
            background: #e0e0e0;
            border-radius: 3px;
            overflow: hidden;
            margin-top: 8px;
        }}
        .progress-fill {{
            height: 100%;
            background: linear-gradient(90deg, #667eea, #764ba2);
            border-radius: 3px;
            animation: progress 2s ease-in-out infinite;
            width: 60%;
        }}
        @keyframes progress {{
            0% {{ transform: translateX(-100%); }}
            100% {{ transform: translateX(200%); }}
        }}
        .steps {{
            text-align: left;
            margin-top: 24px;
            border-top: 1px solid #eee;
            padding-top: 20px;
        }}
        .steps h3 {{
            font-size: 16px;
            color: #1a1a2e;
            margin-bottom: 16px;
        }}
        .step {{
            display: flex;
            align-items: flex-start;
            gap: 12px;
            margin-bottom: 14px;
        }}
        .step-num {{
            width: 28px;
            height: 28px;
            background: linear-gradient(135deg, #667eea, #764ba2);
            color: white;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 13px;
            font-weight: 700;
            flex-shrink: 0;
        }}
        .step-text {{
            font-size: 14px;
            color: #444;
            line-height: 1.5;
            padding-top: 3px;
        }}
        .step-text strong {{
            color: #1a1a2e;
        }}
        .warning {{
            background: #fff8e1;
            border: 1px solid #ffca28;
            border-radius: 12px;
            padding: 14px 16px;
            margin-top: 16px;
            text-align: left;
        }}
        .warning-title {{
            font-size: 13px;
            font-weight: 600;
            color: #f57f17;
            margin-bottom: 4px;
        }}
        .warning-text {{
            font-size: 12px;
            color: #795548;
            line-height: 1.5;
        }}
        .hidden {{ display: none; }}
    </style>
</head>
<body>
    <div class="card">
        <div class="logo">&#128274;</div>
        <h1>RR Locker</h1>
        <p class="subtitle">Device Management & Protection App</p>
        
        <div id="statusWaiting" class="status waiting">
            &#9889; Ready to download. Tap the button below.
        </div>
        
        <div id="statusDownloading" class="status downloading hidden">
            &#9989; Download started! Check your notification bar.
            <div class="progress-bar"><div class="progress-fill"></div></div>
        </div>

        <a id="downloadBtn" href="{download_url}" class="download-btn" onclick="startDownload()">
            <span class="icon">&#11015;</span> Download RR Locker
        </a>
        
        <div class="steps">
            <h3>&#128221; Installation Steps:</h3>
            
            <div class="step">
                <div class="step-num">1</div>
                <div class="step-text">Tap <strong>"Download RR Locker"</strong> button above</div>
            </div>
            
            <div class="step">
                <div class="step-num">2</div>
                <div class="step-text">When download completes, tap the notification or go to <strong>Downloads</strong> folder</div>
            </div>
            
            <div class="step">
                <div class="step-num">3</div>
                <div class="step-text">If prompted, tap <strong>"Settings"</strong> &rarr; Enable <strong>"Allow from this source"</strong> &rarr; Go back and tap <strong>"Install"</strong></div>
            </div>
            
            <div class="step">
                <div class="step-num">4</div>
                <div class="step-text">Open the app and enter your <strong>Server URL</strong> to enroll the device</div>
            </div>
            
            <div class="step">
                <div class="step-num">5</div>
                <div class="step-text">Grant all permissions when asked ï¿½ <strong>required</strong> for device protection</div>
            </div>
        </div>

        <div class="warning">
            <div class="warning-title">&#9888;&#65039; Important</div>
            <div class="warning-text">
                You must allow <strong>"Install unknown apps"</strong> from your browser when prompted. 
                This is required because the app is not from Play Store.
                Go to: <strong>Settings &rarr; Apps &rarr; Special access &rarr; Install unknown apps</strong>
            </div>
        </div>
    </div>

    <script>
        // Auto-download after 1.5 seconds
        setTimeout(function() {{
            startDownload();
        }}, 1500);
        
        function startDownload() {{
            document.getElementById('statusWaiting').classList.add('hidden');
            document.getElementById('statusDownloading').classList.remove('hidden');
            
            // Trigger download
            window.location.href = '{download_url}';
        }}
    </script>
</body>
</html>"""
    return HTMLResponse(content=html)


@router.get("/qr-code")
async def get_qr_code(request: Request):
    """Generate QR code pointing to the install landing page"""
    # QR points to the install landing page which auto-downloads from Google Drive
    base_url = str(request.base_url).rstrip("/")
    install_url = f"{base_url}/api/v1/app/install"
    
    # Generate QR code
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_H,
        box_size=10,
        border=4,
    )
    qr.add_data(install_url)
    qr.make(fit=True)
    
    img = qr.make_image(fill_color="#1a1a2e", back_color="white")
    
    # Convert to bytes
    img_buffer = io.BytesIO()
    img.save(img_buffer, format="PNG")
    img_buffer.seek(0)
    
    return StreamingResponse(
        img_buffer,
        media_type="image/png",
        headers={
            "Content-Disposition": "inline; filename=rrlocker-qr.png",
            "Cache-Control": "no-cache"
        }
    )


@router.get("/info")
async def app_info(request: Request):
    """Get app distribution info including download URL and QR code URL"""
    base_url = str(request.base_url).rstrip("/")
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    
    apk_exists = os.path.isfile(apk_path)
    apk_size = os.path.getsize(apk_path) if apk_exists else 0
    
    return {
        "app_name": "RR Locker",
        "apk_available": True,
        "apk_size_mb": round(apk_size / (1024 * 1024), 2) if apk_exists else 9.6,
        "download_url": APK_DOWNLOAD_URL,
        "install_page_url": f"{base_url}/api/v1/app/install",
        "qr_code_url": f"{base_url}/api/v1/app/qr-code",
        "provisioning_qr_url": f"{base_url}/api/v1/app/provisioning-qr",
        "local_download_url": f"{base_url}/api/v1/app/download-local",
        "device_admin_component": DEVICE_ADMIN_COMPONENT,
        "adb_command": f"adb shell dpm set-device-owner {DEVICE_ADMIN_COMPONENT}",
    }


@router.get("/check-update")
async def check_update(
    current_version: int = 0,
    current_version_name: str = "",
):
    """Check if a newer version of the app is available.
    
    Called by:
    - Android app periodically (every 6-12 hours)  
    - Android app on FCM UPDATE_APP command
    - Dashboard to show version info
    
    Args:
        current_version: Device's current versionCode (e.g. 2)
        current_version_name: Device's current versionName (e.g. "2.0.0")
    """
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    
    # Read version info from a sidecar JSON file next to the APK
    version_file = os.path.join(APK_DIR, "version.json")
    latest_version = 2
    latest_version_name = "2.0.0"
    changelog = ""
    force_update = False
    min_supported_version = 1
    
    if os.path.isfile(version_file):
        try:
            with open(version_file, "r") as f:
                info = json.load(f)
            latest_version = info.get("version_code", latest_version)
            latest_version_name = info.get("version_name", latest_version_name)
            changelog = info.get("changelog", "")
            force_update = info.get("force_update", False)
            min_supported_version = info.get("min_supported_version", 1)
        except Exception as e:
            logger.warning(f"Failed to read version.json: {e}")
    
    has_update = current_version < latest_version
    apk_available = os.path.isfile(apk_path)
    apk_size = os.path.getsize(apk_path) if apk_available else 0
    
    # Force update if device version is below minimum supported
    if current_version < min_supported_version:
        force_update = True
    
    return {
        "has_update": has_update,
        "latest_version": latest_version,
        "latest_version_name": latest_version_name,
        "current_version": current_version,
        "apk_url": APK_PROVISIONING_URL if apk_available else APK_DOWNLOAD_URL,
        "apk_size": apk_size,
        "force_update": force_update and has_update,
        "changelog": changelog,
        "min_supported_version": min_supported_version,
    }


def _compute_apk_checksum():
    """Compute SHA-256 checksum of APK file for provisioning verification"""
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    if not os.path.isfile(apk_path):
        return None
    
    sha256 = hashlib.sha256()
    with open(apk_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha256.update(chunk)
    
    # Android expects URL-safe base64 encoded SHA-256
    return base64.urlsafe_b64encode(sha256.digest()).decode("utf-8").rstrip("=")


def _compute_apk_signature_checksum():
    """Compute SHA-256 checksum of the APK signing certificate.
    Required for Android 12+ (API 31+). Without this, Android 13+ shows
    'Contact your IT administrator' during QR provisioning.
    """
    import struct
    import zipfile
    
    apk_path = os.path.join(APK_DIR, APK_FILENAME)
    if not os.path.isfile(apk_path):
        return None
    
    try:
        # Try APK Signature Scheme v2/v3 first
        cert_der = _extract_cert_from_apk_signing_block(apk_path)
        
        if cert_der is None:
            # Fallback: v1 JAR signature (META-INF/*.RSA)
            cert_der = _extract_cert_v1(apk_path)
        
        if cert_der is None:
            logger.warning("No signing certificate found in APK")
            return None
        
        sha256 = hashlib.sha256(cert_der)
        result = base64.urlsafe_b64encode(sha256.digest()).decode("utf-8").rstrip("=")
        logger.info(f"APK signature checksum computed: {result}")
        return result
    except Exception as e:
        logger.error(f"Failed to compute APK signature checksum: {e}")
        return None


def _extract_cert_from_apk_signing_block(apk_path):
    """Extract signing certificate from APK Signature Scheme v2/v3."""
    import struct
    try:
        with open(apk_path, 'rb') as f:
            data = f.read()
        
        # Find ZIP End of Central Directory
        eocd_offset = -1
        for i in range(len(data) - 22, max(len(data) - 65536 - 22, -1), -1):
            if data[i:i+4] == b'\x50\x4b\x05\x06':
                eocd_offset = i
                break
        if eocd_offset < 0:
            return None
        
        cd_offset = struct.unpack_from('<I', data, eocd_offset + 16)[0]
        if cd_offset < 24:
            return None
        
        magic = data[cd_offset - 16 : cd_offset]
        if magic != b'APK Sig Block 42':
            return None
        
        block_size = struct.unpack_from('<Q', data, cd_offset - 24)[0]
        block_start = cd_offset - block_size - 8
        if block_start < 0:
            return None
        
        pairs_start = block_start + 8
        pairs_end = cd_offset - 24
        offset = pairs_start
        while offset < pairs_end:
            pair_size = struct.unpack_from('<Q', data, offset)[0]
            offset += 8
            pair_id = struct.unpack_from('<I', data, offset)[0]
            pair_data = data[offset + 4 : offset + int(pair_size)]
            if pair_id in (0x7109871a, 0xf05368c0):  # v2 or v3
                cert = _parse_signer_cert(pair_data)
                if cert:
                    return cert
            offset += int(pair_size)
        return None
    except Exception:
        return None


def _parse_signer_cert(data):
    """Parse v2/v3 signer block to extract first X.509 certificate."""
    import struct
    try:
        off = 0
        off += 4  # signers_size
        off += 4  # signer_size
        signed_data_size = struct.unpack_from('<I', data, off)[0]
        off += 4
        signed_data = data[off : off + signed_data_size]
        inner = 0
        digests_size = struct.unpack_from('<I', signed_data, inner)[0]
        inner += 4 + digests_size
        inner += 4  # certs_size
        cert_size = struct.unpack_from('<I', signed_data, inner)[0]
        inner += 4
        return signed_data[inner : inner + cert_size]
    except Exception:
        return None


def _extract_cert_v1(apk_path):
    """Extract signing certificate from v1 JAR signature using cryptography library."""
    import zipfile
    try:
        from cryptography.hazmat.primitives.serialization import pkcs7
        from cryptography.hazmat.primitives.serialization import Encoding
    except ImportError:
        logger.warning("cryptography library not available for PKCS#7 parsing")
        return _extract_cert_v1_manual(apk_path)
    
    try:
        with zipfile.ZipFile(apk_path, 'r') as zf:
            for name in zf.namelist():
                upper = name.upper()
                if upper.startswith('META-INF/') and (
                    upper.endswith('.RSA') or upper.endswith('.DSA') or upper.endswith('.EC')
                ):
                    pkcs7_data = zf.read(name)
                    try:
                        certs = pkcs7.load_der_pkcs7_certificates(pkcs7_data)
                        if certs:
                            cert_der = certs[0].public_bytes(Encoding.DER)
                            logger.info(f"v1 cert extracted via cryptography lib: {len(cert_der)} bytes")
                            return cert_der
                    except Exception as e:
                        logger.debug(f"cryptography PKCS#7 parse failed: {e}")
                        cert = _extract_cert_from_pkcs7(pkcs7_data)
                        return cert if cert else pkcs7_data
        return None
    except Exception:
        return None


def _extract_cert_v1_manual(apk_path):
    """Manual fallback for v1 cert extraction."""
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


def _extract_cert_from_pkcs7(data):
    """Extract first X.509 certificate DER from PKCS#7 SignedData."""
    try:
        def read_tl(d, o):
            if o >= len(d): return None, 0, 0
            tag = d[o]; o += 1
            if o >= len(d): return tag, 0, 1
            lb = d[o]; o += 1
            if lb < 0x80: return tag, lb, 2
            nb = lb & 0x7F
            if nb == 0 or o + nb > len(d): return tag, 0, 2
            length = int.from_bytes(d[o:o+nb], 'big')
            return tag, length, 2 + nb

        def find_certs(d, o, end):
            certs = []
            while o < end:
                tag, length, hs = read_tl(d, o)
                if tag is None or (length == 0 and hs <= 1): break
                cs = o + hs; ce = cs + length
                if ce > end: break
                if tag == 0xA0:
                    certs.extend(find_certs(d, cs, ce))
                elif tag == 0x30 and cs < ce:
                    it, _, _ = read_tl(d, cs)
                    if it == 0x30 and len(d[o:ce]) > 100:
                        certs.append(d[o:ce])
                o = ce
            return certs

        certs = find_certs(data, 0, len(data))
        return certs[0] if certs else None
    except Exception:
        return None


@router.get("/provisioning-qr")
async def provisioning_qr_code(request: Request):
    """
    Generate Android Device Owner provisioning QR code.
    
    NOTE: For full Zero Touch Enrollment with WiFi auto-connect and
    auto-enrollment, use the /api/v1/zte/provisioning-qr endpoint instead.
    This endpoint generates a basic provisioning QR without ZTE features.
    
    This QR code is scanned during the Android setup wizard (after factory reset)
    to automatically:
    1. Download the RR Locker APK
    2. Install it
    3. Set it as Device Owner
    
    NO PC/ADB needed! Just factory reset + scan QR during setup.
    
    How to use:
    - Factory reset the phone (or use a brand new phone)
    - At the welcome screen, tap 6 times on the welcome text
    - Camera opens for QR scanning
    - Scan this QR code
    - Phone automatically sets up RR Locker as Device Owner
    """
    base_url = str(request.base_url).rstrip("/")
    
    # Use backend's own endpoint for provisioning ï¿½ Samsung requires direct APK with HEAD support
    apk_download_url = f"{base_url}/api/v1/app/download"
    
    # Compute checksum from actual APK file on server
    checksum = _compute_apk_checksum() or APK_CHECKSUM
    
    # Build Android managed provisioning payload
    # IMPORTANT: Keep MINIMAL for Samsung compatibility.
    # Samsung QR parser fails on nested objects (PROVISIONING_ADMIN_EXTRAS_BUNDLE)
    provisioning_data = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": DEVICE_ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_download_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ALLOW_OFFLINE": True,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 2,
    }
    
    # PACKAGE_CHECKSUM ï¿½ REQUIRED for Android 9-11 (Samsung One UI 1/2/3)
    provisioning_data["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = checksum
    
    # SIGNATURE_CHECKSUM ï¿½ REQUIRED for Android 12+ (API 31+)
    sig_checksum = _compute_apk_signature_checksum()
    provisioning_data["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum or APK_SIG_CHECKSUM
    
    qr_data = json.dumps(provisioning_data)
    
    qr = qrcode.QRCode(
        version=None,  # Auto-size
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
            "Content-Disposition": "inline; filename=rrlocker-provisioning-qr.png",
            "Cache-Control": "no-cache"
        }
    )


@router.get("/provisioning-data")
async def provisioning_data(request: Request):
    """
    Return the provisioning QR data as JSON (for dashboard to generate QR client-side).
    """
    base_url = str(request.base_url).rstrip("/")
    
    # Use backend's own endpoint ï¿½ Samsung requires direct APK download with HEAD support
    apk_download_url = f"{base_url}/api/v1/app/download"
    
    # Compute checksum from actual APK file on server
    checksum = _compute_apk_checksum() or APK_CHECKSUM
    
    data = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": DEVICE_ADMIN_COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_download_url,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": True,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ALLOW_OFFLINE": True,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE": 2,
    }
    
    # PACKAGE_CHECKSUM ï¿½ REQUIRED for Android 9-11 (Samsung One UI 1/2/3)
    data["android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"] = checksum
    
    # SIGNATURE_CHECKSUM ï¿½ REQUIRED for Android 12+ (API 31+)
    sig_checksum = _compute_apk_signature_checksum()
    data["android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] = sig_checksum or APK_SIG_CHECKSUM
    
    return {
        "provisioning_data": data,
        "qr_string": json.dumps(data),
        "instructions": {
            "en": [
                "Factory reset the phone (or use brand new phone)",
                "At the welcome screen, tap 6 times on the welcome text",
                "Camera will open for QR scanning", 
                "Scan this QR code",
                "Phone automatically downloads, installs app, and sets Device Owner",
                "No PC/ADB needed!"
            ],
            "bn": [
                "??? Factory Reset ???? (???? ???? ??? ??????? ????)",
                "Welcome screen ? 6 ??? tap ????",
                "Camera open ??? QR scan ???? ????",
                "?? QR code scan ????",
                "??? automatically app download, install ??? Device Owner ??? ????",
                "???? PC/ADB ????? ??!"
            ]
        },
        "device_admin_component": DEVICE_ADMIN_COMPONENT,
        "adb_command": f"adb shell dpm set-device-owner {DEVICE_ADMIN_COMPONENT}",
    }
