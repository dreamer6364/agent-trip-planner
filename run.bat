@echo off
chcp 65001 >nul 2>&1
title TripForge - Full Stack Launcher
cd /d "%~dp0"

echo.
echo  ============================================
echo    TripForge launcher (unified: start-all.ps1)
echo  ============================================
echo    Usage:
echo      run.bat                 start all services
echo      run.bat -Build          build backend first
echo      run.bat -BuildFrontend  also build frontend
echo      run.bat -Action status  show status
echo      run.bat -Action stop    stop all services
echo      run.bat -Action restart restart all services
echo  ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
pause
exit /b %RC%
