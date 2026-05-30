@echo off
REM ============================================================
REM  RR Locker - One-Click Wireless Device Owner Setup
REM  -----------------------------------------------------------
REM  Downloads the helper Python scripts from the dashboard
REM  server, then runs the wireless provisioner.
REM
REM  NO USB CABLE NEEDED. Only Wi-Fi.
REM
REM  Requirements (one-time on the phone):
REM    Settings -> About -> tap "Build number" 7 times
REM    Settings -> Developer options -> Wireless debugging -> ON
REM    Inside Wireless debugging: tap "Pair device with pairing code"
REM    -> the phone shows: IP : PORT  and a 6-digit code
REM ============================================================

setlocal EnableDelayedExpansion

REM --- locate Python -----------------------------------------------------
set "PYCMD="
where python >nul 2>nul && set "PYCMD=python"
if "%PYCMD%"=="" where py >nul 2>nul && set "PYCMD=py -3"
if "%PYCMD%"=="" (
    echo [ERROR] Python 3.8+ is required but was not found on PATH.
    echo         Install from https://www.python.org/downloads/  and re-run.
    pause
    exit /b 1
)

REM --- locate adb --------------------------------------------------------
set "ADB="
for %%P in (
    "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    "%ANDROID_HOME%\platform-tools\adb.exe"
    "%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
    "C:\Android\Sdk\platform-tools\adb.exe"
    "C:\platform-tools\adb.exe"
) do (
    if exist %%~P set "ADB=%%~P"
)
where adb >nul 2>nul && if "%ADB%"=="" set "ADB=adb"
if "%ADB%"=="" (
    echo [WARN] adb.exe not found. The Python script will try to locate it,
    echo        but install Android platform-tools if it fails:
    echo        https://developer.android.com/tools/releases/platform-tools
)

REM --- prepare temp folder ----------------------------------------------
set "WORK=%TEMP%\rrlocker_wifi_setup"
if not exist "%WORK%" mkdir "%WORK%"
cd /d "%WORK%"

REM --- figure out server base URL ---------------------------------------
REM  If launched by double-click from a download, default to live API host.
REM  Override with:  setup_owner_wifi.bat https://your-server.com
set "BASE=%~1"
if "%BASE%"=="" set "BASE=https://riadrayhan111-rr-locker-api.hf.space"

echo [INFO] Downloading helper scripts from %BASE% ...
powershell -NoProfile -Command ^
    "$ErrorActionPreference='Stop'; " ^
    "$base='%BASE%'.TrimEnd('/'); " ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " ^
    "Invoke-WebRequest -UseBasicParsing -Uri \"$base/activate_device_owner.py\" -OutFile activate_device_owner.py; " ^
    "Invoke-WebRequest -UseBasicParsing -Uri \"$base/wireless_owner_setup.py\"   -OutFile wireless_owner_setup.py;"
if errorlevel 1 (
    echo.
    echo [ERROR] Could not download helper scripts from %BASE%.
    echo         Check internet connectivity and that the URL is reachable.
    echo         You can also pass a different URL:
    echo            setup_owner_wifi.bat https://your-dashboard-url
    pause
    exit /b 2
)

echo.
echo ===============================================================
echo   Wireless Device Owner Setup
echo   On the phone, open  Settings - Developer options -
echo                       Wireless debugging - Pair device with code
echo   Note the IP : PORT and the 6-digit code shown.
echo ===============================================================
echo.

%PYCMD% "%WORK%\wireless_owner_setup.py" %2 %3 %4 %5 %6 %7 %8 %9

echo.
pause
endlocal
