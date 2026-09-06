@echo off
title Lumi - Device Mirroring
echo ========================================================
echo   Lumi - Android Device Mirroring & Test Runner
echo ========================================================
echo.

set SCRCPY_PATH=C:\Users\hassa\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1
set ADB_PATH=C:\Users\hassa\AppData\Local\Android\Sdk\platform-tools\adb.exe

if not exist "%SCRCPY_PATH%\scrcpy.exe" (
    echo [ERROR] scrcpy not found at %SCRCPY_PATH%
    pause
    exit /b 1
)

echo [1/3] Checking connected Android devices...
"%ADB_PATH%" devices -l
echo.

echo [2/3] Installing latest Lumi debug APK...
"%ADB_PATH%" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
echo.

echo [3/3] Launching Lumi on phone & opening Screen Mirroring...
"%ADB_PATH%" shell am start -n ai.lumi/.MainActivity

echo.
echo Starting screen mirror window (Press Ctrl+F for fullscreen, Ctrl+Q to exit)...
"%SCRCPY_PATH%\scrcpy.exe" --stay-awake --turn-screen-off --window-title "Lumi Phone Mirror (iQOO 15)"

pause
