@echo off
REM ============================================================
REM  RR Locker - Wireless Device Owner Setup (NO USB CABLE)
REM  -----------------------------------------------------------
REM  Sets EMI Locker as Device Owner over Wi-Fi only.
REM  Requires (one-time on the phone):
REM     Settings -> Developer options -> Wireless debugging -> ON
REM     Inside Wireless debugging: tap 'Pair device with pairing code'
REM     The phone will show:  IP : PORT  and a 6-digit code
REM ============================================================

setlocal
cd /d "%~dp0"

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python "%~dp0wireless_owner_setup.py" %*
    goto :end
)

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py -3 "%~dp0wireless_owner_setup.py" %*
    goto :end
)

echo [ERROR] Python is not installed or not on PATH.
echo         Install Python 3.8+ from https://www.python.org/downloads/
echo         then re-run this script.

:end
echo.
pause
endlocal
