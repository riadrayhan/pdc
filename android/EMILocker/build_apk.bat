@echo off
cd /d "c:\Users\BM COMPUTERS\Documents\project\emi_locker\android\EMILocker"
"%~dp0gradlew.bat" assembleDebug
echo Build completed. Check exit code: %ERRORLEVEL%
pause
