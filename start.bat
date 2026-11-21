@echo off
REM start.bat : bat backend (Docker) + mo giao dien Electron tren may.
REM Lan dau tu tai thu vien + Electron. Tat: stop.bat
setlocal
cd /d "%~dp0"

where docker >nul 2>nul || (echo [LOI] Chua cai Docker Desktop. & goto :fail)
where node   >nul 2>nul || (echo [LOI] Chua cai Node.js: https://nodejs.org & goto :fail)

echo [1/3] Khoi dong backend trong Docker...
docker compose up -d --build --wait backend
if errorlevel 1 (echo [LOI] Backend khoi dong that bai. & goto :fail)

cd /d "%~dp0desktop-app"

echo [2/3] Kiem tra thu vien + Electron...
if not exist "node_modules\zustand" (
    echo     Dang cai thu vien npm, lan dau mat vai phut...
    call npm install --no-audit --no-fund
    if errorlevel 1 (echo [LOI] npm install that bai. & goto :fail)
)
if not exist "node_modules\electron\dist\electron.exe" (
    echo     Dang tai Electron...
    call node node_modules\electron\install.js
)
if not exist "node_modules\electron\dist\electron.exe" (echo [LOI] Khong tai duoc Electron. Kiem tra mang. & goto :fail)

echo [3/3] Mo giao dien...
echo API: http://localhost:8080/api/search?q=may+tinh   ^|   Tat het: stop.bat
call npm run dev
endlocal
exit /b 0

:fail
echo.
pause
endlocal
exit /b 1
