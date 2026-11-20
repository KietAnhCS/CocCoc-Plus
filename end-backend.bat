@echo off
setlocal

REM Dừng backend search engine đang lắng nghe ở cổng 8080.

set "FOUND="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8080 .*LISTENING"') do (
    set "FOUND=1"
    echo Dừng tiến trình PID %%p ở cổng 8080...
    taskkill /PID %%p /F >nul 2>nul
)
if not defined FOUND echo Không có gì đang chạy ở cổng 8080.

endlocal
