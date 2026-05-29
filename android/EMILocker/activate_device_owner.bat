@echo off
REM ============================================================
REM  RR Locker - Activate Device Owner (wrapper for Python)
REM ============================================================
REM  The pure-batch version cannot detect failures from
REM  `adb shell dpm set-device-owner` because adb shell always
REM  returns exit code 0. This wrapper runs the Python script
REM  which parses real stdout and verifies via dumpsys.
REM ============================================================

setlocal
cd /d "%~dp0"

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python "%~dp0activate_device_owner.py" %*
    goto :end
)

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py -3 "%~dp0activate_device_owner.py" %*
    goto :end
)

echo [ERROR] Python is not installed or not on PATH.
echo         Install Python 3.8+ from https://www.python.org/downloads/
echo         then re-run this script.

:end
echo.
pause
endlocal
