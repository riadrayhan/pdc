@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>nul
title RR Locker - One-Click Device Owner Setup
color 0B

echo ============================================================
echo    RR Locker - One-Click Device Owner Setup
echo    Works on Android 7.0+ (Samsung / Xiaomi / Vivo / Oppo)
echo ============================================================
echo.
echo  Requirements (do these on the phone FIRST):
echo     1. Settings -^> About phone -^> tap Build number 7x
echo     2. Settings -^> Developer options -^> USB Debugging ON
echo     3. Settings -^> Accounts -^> Remove ALL Google accounts
echo     4. Connect phone via USB and accept the RSA prompt
echo.
echo  Press any key when ready...
pause >nul
echo.

REM ------------------------------------------------------------
REM Locate ADB
REM ------------------------------------------------------------
set "ADB="
where adb >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    set "ADB=adb"
) else (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "%~dp0platform-tools\adb.exe" (
        set "ADB=%~dp0platform-tools\adb.exe"
    ) else if exist "%~dp0adb.exe" (
        set "ADB=%~dp0adb.exe"
    )
)

if not defined ADB (
    echo [ERROR] adb.exe not found.
    echo         Download platform-tools:
    echo         https://developer.android.com/tools/releases/platform-tools
    echo         Extract it and place this .bat inside that folder, then re-run.
    pause
    exit /b 1
)
echo [OK] Using ADB: !ADB!
echo.

REM ------------------------------------------------------------
REM Detect device (handle unauthorized state properly)
REM ------------------------------------------------------------
"!ADB!" start-server >nul 2>nul

set DEVICE_SERIAL=
set UNAUTHORIZED=0
for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
    if "%%b"=="device" (
        if not defined DEVICE_SERIAL set "DEVICE_SERIAL=%%a"
    )
    if "%%b"=="unauthorized" set UNAUTHORIZED=1
)

if !UNAUTHORIZED! EQU 1 if not defined DEVICE_SERIAL (
    echo [!] Device is UNAUTHORIZED.
    echo     On the phone tap "Allow USB debugging" (check "Always allow").
    echo     Then press any key to retry...
    pause >nul
    set DEVICE_SERIAL=
    for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
        if "%%b"=="device" if not defined DEVICE_SERIAL set "DEVICE_SERIAL=%%a"
    )
)

if not defined DEVICE_SERIAL (
    echo [ERROR] No authorized device found.
    echo         - Is the USB cable connected (data, not charge-only)?
    echo         - Is USB Debugging enabled?
    echo         - Did you accept the RSA prompt on the phone?
    pause
    exit /b 1
)
echo [OK] Device ready: !DEVICE_SERIAL!
echo.

REM ------------------------------------------------------------
REM Step 1 - Disable Play Protect / package verifier
REM ------------------------------------------------------------
echo ============================================================
echo   Step 1/5: Disabling Play Protect / package verifier
echo ============================================================
"!ADB!" shell settings put global package_verifier_enable 0 >nul 2>nul
"!ADB!" shell settings put global verifier_verify_adb_installs 0 >nul 2>nul
"!ADB!" shell settings put global upload_apk_enable 0 >nul 2>nul
"!ADB!" shell settings put secure package_verifier_user_consent -1 >nul 2>nul
"!ADB!" shell settings put global package_verifier_state 0 >nul 2>nul
echo [OK] Done.
echo.

REM ------------------------------------------------------------
REM Step 2 - Install APK (if present alongside this .bat)
REM ------------------------------------------------------------
echo ============================================================
echo   Step 2/5: Installing RR Locker APK
echo ============================================================
set APK_PATH=
if exist "%~dp0app-release.apk" set "APK_PATH=%~dp0app-release.apk"
if not defined APK_PATH if exist "app-release.apk" set "APK_PATH=%CD%\app-release.apk"

set ALREADY=0
"!ADB!" shell pm list packages 2>nul | findstr /c:"package:com.riad.rrlkr" >nul 2>nul
if !ERRORLEVEL! EQU 0 set ALREADY=1

if defined APK_PATH (
    echo Installing !APK_PATH! ...
    "!ADB!" install -r -g "!APK_PATH!" 2>&1 | findstr /i "Success Failure"
    "!ADB!" shell pm list packages 2>nul | findstr /c:"package:com.riad.rrlkr" >nul 2>nul
    if !ERRORLEVEL! NEQ 0 (
        echo Retrying with -d flag...
        "!ADB!" install -r -d -g "!APK_PATH!" 2>&1 | findstr /i "Success Failure"
    )
) else (
    if !ALREADY! EQU 0 (
        echo [ERROR] app-release.apk not found next to this .bat,
        echo         and the app is not installed on the phone.
        echo         Place app-release.apk here and re-run.
        pause
        exit /b 1
    ) else (
        echo [OK] App is already installed on the device.
    )
)

"!ADB!" shell pm list packages 2>nul | findstr /c:"package:com.riad.rrlkr" >nul 2>nul
if !ERRORLEVEL! NEQ 0 (
    echo [ERROR] RR Locker package is still not present after install.
    pause
    exit /b 1
)
echo [OK] App verified on device.
echo.

REM ------------------------------------------------------------
REM Step 3 - Detect blocking accounts BEFORE attempting provisioning
REM ------------------------------------------------------------
echo ============================================================
echo   Step 3/5: Checking for blocking user accounts
echo ============================================================
set ACCOUNTS_FOUND=0
for /f "tokens=*" %%L in ('"!ADB!" shell dumpsys account 2^>nul ^| findstr /c:"Account {"') do (
    set ACCOUNTS_FOUND=1
    echo   - %%L
)
if !ACCOUNTS_FOUND! EQU 1 (
    echo.
    echo [ERROR] The phone still has user accounts.
    echo         `dpm set-device-owner` will FAIL.
    echo         Settings -^> Accounts -^> tap each -^> Remove account,
    echo         then re-run this script.
    pause
    exit /b 1
)
echo [OK] No accounts present.
echo.

REM ------------------------------------------------------------
REM Step 4 - Check existing Device Owner
REM ------------------------------------------------------------
echo ============================================================
echo   Step 4/5: Checking existing Device Owner
echo ============================================================
"!ADB!" shell dumpsys device_policy 2>nul | findstr /i "com.riad.rrlkr" >nul 2>nul
if !ERRORLEVEL! EQU 0 (
    echo [OK] Device Owner is ALREADY set to com.riad.rrlkr.
    echo      Nothing to do. Open RR Locker on the phone.
    pause
    exit /b 0
)

"!ADB!" shell dumpsys device_policy 2>nul | findstr /i "Device Owner" >nul 2>nul
if !ERRORLEVEL! EQU 0 (
    "!ADB!" shell dumpsys device_policy 2>nul | findstr /i "Device Owner"
    echo.
    echo [ERROR] A DIFFERENT app is already Device Owner.
    echo         Only ONE device owner is allowed per device.
    echo         Factory reset the phone and re-run BEFORE adding any account.
    pause
    exit /b 1
)
echo [OK] No existing Device Owner. Ready to provision.
echo.

REM ------------------------------------------------------------
REM Step 5 - Set Device Owner and verify via dumpsys (NOT errorlevel)
REM ------------------------------------------------------------
echo ============================================================
echo   Step 5/5: Setting Device Owner
echo ============================================================
"!ADB!" shell dpm set-device-owner com.riad.rrlkr/.admin.EMIDeviceAdminReceiver
echo.

REM Verify by reading dumpsys -- adb shell exit codes are unreliable
"!ADB!" shell dumpsys device_policy 2>nul | findstr /i "com.riad.rrlkr" >nul 2>nul
if !ERRORLEVEL! EQU 0 (
    echo.
    echo ============================================================
    echo           SETUP COMPLETE
    echo.
    echo    Play Protect : DISABLED
    echo    Device Owner : ACTIVE (com.riad.rrlkr)
    echo    App          : INSTALLED
    echo.
    echo    You can now add Google accounts back if needed.
    echo    Open RR Locker on the phone to enroll the device.
    echo ============================================================
    "!ADB!" shell settings put global package_verifier_enable 0 >nul 2>nul
    "!ADB!" shell settings put global verifier_verify_adb_installs 0 >nul 2>nul
    "!ADB!" shell settings put secure package_verifier_user_consent -1 >nul 2>nul
) else (
    echo.
    echo [ERROR] Device Owner was NOT set. Most common causes:
    echo.
    echo   - "already has an account":
    echo        Remove ALL accounts on the phone and retry.
    echo   - "already provisioned" / "user setup completed":
    echo        Phone finished setup wizard with accounts already added.
    echo        Factory reset and run THIS script BEFORE adding any account.
    echo   - "not found" / "unknown admin":
    echo        Re-install the APK and retry.
    echo   - Secondary user:
    echo        Switch to primary user (user 0) and retry.
    echo.
)

pause
endlocal
