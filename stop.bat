@echo off
REM stop.bat : tat giao dien Electron + backend Docker.
setlocal
cd /d "%~dp0"
echo Dang tat giao dien Electron...
powershell -NoProfile -Command "Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*CocCoc-Plus*' } | Stop-Process -Force"
echo Dang tat backend Docker...
docker compose --profile crawl down
echo Da tat.
endlocal
