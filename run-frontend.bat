@echo off
setlocal

set "ELECTRON_BIN=node_modules\electron\dist\electron.exe"

cd /d "%~dp0browser-app" 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay thu muc "%~dp0browser-app".
    echo       File .bat nay phai nam o THU MUC GOC cua repo, canh docker-compose.yml.
    goto :fail
)

if not exist "package.json" (
    echo [LOI] Khong thay package.json trong "%CD%".
    echo       Thu muc browser-app co ve khong day du.
    goto :fail
)

where node >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Node.js.
    echo       Cai dat tai https://nodejs.org roi mo lai cua so nay.
    goto :fail
)
for /f "delims=" %%v in ('node --version') do echo Node.js %%v

echo.
echo Dang dong bo thu vien theo package.json...
echo.
call npm install --no-audit --no-fund

if not exist "node_modules" (
    echo.
    echo [LOI] npm install that bai - van chua co node_modules.
    echo       Cuon len xem thong bao loi cua npm o tren.
    goto :fail
)

if not exist "node_modules\zustand" (
    echo.
    echo [LOI] Thieu goi zustand du npm install da chay xong.
    echo       Thu xoa node_modules roi chay lai file nay.
    goto :fail
)

if not exist "%ELECTRON_BIN%" (
    echo.
    echo Chua co ban chay Electron, dang tai ve... ^(mat vai phut^)
    echo.
    call node "node_modules\electron\install.js"
)

if not exist "%ELECTRON_BIN%" (
    echo.
    echo [LOI] Khong tai duoc ban chay Electron.
    echo       Kiem tra ket noi mang, hoac chay tay:
    echo           cd browser-app ^&^& node node_modules\electron\install.js
    goto :fail
)

echo.
call npm run dev
if errorlevel 1 goto :fail

endlocal
exit /b 0

:fail
echo.
echo Nhan phim bat ky de dong...
pause >nul
endlocal
exit /b 1
