@echo off
chcp 65001 >nul 2>&1
title TripForge - Stop All Services
cd /d "%~dp0"

echo.
echo  Stopping TripForge services (only this project, other Java apps are kept)...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" -Action stop

echo.
pause
