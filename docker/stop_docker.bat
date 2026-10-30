@echo off
echo Stopping CacheTune Docker Containers...
cd /d "%~dp0"
docker compose down
echo All CacheTune containers stopped.
pause
