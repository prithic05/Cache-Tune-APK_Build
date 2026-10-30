@echo off
echo ========================================================
echo   CacheTune Cloud Lossless Engine - Docker Startup
echo ========================================================
echo.

cd /d "%~dp0"

echo [1/2] Checking Docker Desktop status...
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker is not running or not installed.
    echo Please start Docker Desktop on your laptop first!
    pause
    exit /b 1
)

echo [2/2] Starting CacheTune Backend & Database...
docker compose up -d --build

echo.
echo ========================================================
echo   CacheTune Backend is running!
echo   Stream & API: http://localhost:8000
echo   API Docs:     http://localhost:8000/docs
echo ========================================================
echo.
pause
