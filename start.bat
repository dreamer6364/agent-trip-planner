@echo off
chcp 65001 >nul 2>&1
title TripForge - Start All Services
cd /d "%~dp0"

echo.
echo  ============================================
echo    TripForge one-click launcher
echo  ============================================
echo    Usage:
echo      start.bat                 start all services
echo      start.bat -Build          build backend first
echo      start.bat -BuildFrontend  also build frontend
echo      start.bat -Action status  show status
echo      start.bat -Action stop    stop all services
echo      start.bat -Action restart restart all services
echo  ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
pause
exit /b %RC%
