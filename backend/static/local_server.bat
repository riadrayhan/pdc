@echo off
chcp 65001 >nul 2>nul
title RR Locker - Local Provisioning Server
color 0A

echo ╔══════════════════════════════════════════════════╗
echo ║   RR Locker - Offline QR Provisioning Server    ║
echo ║   Android 13+ Play Protect Bypass Solution      ║
echo ╚══════════════════════════════════════════════════╝
echo.

REM Check if app-release.apk exists in current folder
if not exist "app-release.apk" (
    echo [ERROR] app-release.apk not found in current folder!
    echo.
    echo Please copy app-release.apk to the same folder as this script.
    echo.
    pause
    exit /b 1
)

REM Get file size
for %%A in (app-release.apk) do set APK_SIZE=%%~zA
echo [OK] APK found: app-release.apk (%APK_SIZE% bytes)
echo.

REM Detect local IP address
echo Detecting your PC's IP address...
set LOCAL_IP=
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    for /f "tokens=1" %%b in ("%%a") do (
        if not defined LOCAL_IP set LOCAL_IP=%%b
    )
)

if not defined LOCAL_IP (
    echo [WARNING] Could not auto-detect IP. Using 192.168.43.1
    set LOCAL_IP=192.168.43.1
)

echo [OK] Your PC's IP: %LOCAL_IP%
echo.

REM Check if Python is available
where python >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    where python3 >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo [ERROR] Python not found!
        echo.
        echo Please install Python from python.org
        echo Or use the manual QR code method.
        echo.
        pause
        exit /b 1
    )
    set PYTHON=python3
) else (
    set PYTHON=python
)

set SERVER_PORT=8080

echo ══════════════════════════════════════════════════
echo   SETUP INSTRUCTIONS (Follow these steps):
echo ══════════════════════════════════════════════════
echo.
echo   1. PC te WiFi Hotspot ON korun:
echo      Settings → Network → Mobile Hotspot → ON
echo      (Mobile Data OFF rakhun - Internet laagbe na!)
echo.
echo   2. Hotspot er naam ar password note korun
echo.
echo   3. Phone Factory Reset korun
echo.
echo   4. Phone ON hole, PC er Hotspot WiFi te Connect korun
echo.
echo   5. Welcome Screen e 6 bar tap korun → QR Scanner asbe
echo.
echo   6. Nicher QR code ta scan korun:
echo.
echo ══════════════════════════════════════════════════
echo   APK Download URL: http://%LOCAL_IP%:%SERVER_PORT%/app-release.apk
echo ══════════════════════════════════════════════════
echo.
echo   QR Code generate korun:
echo   Dashboard → Device Setup → Offline QR tab e
echo   IP: %LOCAL_IP%, Port: %SERVER_PORT% diye Generate korun
echo.
echo   ALTERNATIVELY: Go to this URL in browser to get QR:
echo   https://rr-locker-api.onrender.com/api/v1/zte/offline-qr-data?ip=%LOCAL_IP%^&port=%SERVER_PORT%
echo.
echo ══════════════════════════════════════════════════
echo   Starting HTTP server on port %SERVER_PORT%...
echo   (Press Ctrl+C to stop)
echo ══════════════════════════════════════════════════
echo.

%PYTHON% -m http.server %SERVER_PORT% --bind 0.0.0.0
