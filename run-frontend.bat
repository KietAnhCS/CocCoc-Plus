@echo off
REM ===========================================================================
REM  VnSearch - chay trinh duyet (Electron + React)
REM
REM  Backend chay bang Docker:  docker compose up -d --build
REM  File nay chi lo phan frontend, vi Electron la ung dung desktop co cua so
REM  nen khong dong goi vao container duoc (container khong co man hinh).
REM ===========================================================================
setlocal

echo.
echo === VnSearch - trinh duyet ===
echo.

REM --- 1. Ve dung thu muc frontend ---
REM %~dp0 la thu muc chua file .bat nay (da co dau \ o cuoi), nen chay duoc
REM du go lenh tu bat ky dau.
cd /d "%~dp0browser-app" 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay thu muc "%~dp0browser-app".
    echo       File .bat nay phai nam o THU MUC GOC cua repo, canh docker-compose.yml.
    goto :fail
)

REM Kiem tra lai bang mot moc chac chan. Neu chi dua vao errorlevel cua `cd`
REM thi mot thu muc rong cung duoc coi la hop le, va cac buoc sau se chay
REM nham cho — dung loi da gap khi thu nghiem file nay.
if not exist "package.json" (
    echo [LOI] Khong thay package.json trong "%CD%".
    echo       Thu muc browser-app co ve khong day du.
    goto :fail
)

REM --- 2. Kiem tra Node.js ---
where node >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Node.js.
    echo       Cai dat tai https://nodejs.org roi mo lai cua so nay.
    goto :fail
)
for /f "delims=" %%v in ('node --version') do echo Node.js %%v

REM --- 3. Cai thu vien neu chua co ---
REM Kiem tra node_modules thay vi chay `npm install` moi lan: npm install mat
REM vai chuc giay ngay ca khi khong co gi thay doi.
if not exist "node_modules" (
    echo.
    echo Chua co node_modules, dang cai dat... ^(lan dau mat vai phut^)
    echo.
    call npm install

    REM KHONG tin errorlevel cua `call npm install`: npm tren Windows la mot
    REM shim .cmd va co truong hop no tra ve 0 du da bao loi. Kiem tra KET QUA
    REM that su thay vi ma tra ve.
    if not exist "node_modules" (
        echo.
        echo [LOI] npm install that bai - van chua co node_modules.
        echo       Cuon len xem thong bao loi cua npm o tren.
        goto :fail
    )
)

REM --- 4. Nhac neu backend chua chay ---
REM Chi la CANH BAO, khong chan: frontend van mo duoc, chi la tim kiem se bao
REM loi cho den khi backend san sang.
curl -s -o nul -m 3 "http://localhost:8080/api/admin/stats" 2>nul
if errorlevel 1 (
    echo.
    echo [CANH BAO] Backend o http://localhost:8080 chua phan hoi.
    echo            Mo mot cua so khac, vao thu muc goc va chay:
    echo                docker compose up -d --build
    echo            Lan dau lap chi muc 5.011 trang mat khoang 15 giay.
    echo.
) else (
    echo Backend: dang chay tai http://localhost:8080
)

REM --- 5. Chay ---
echo.
echo Dang khoi dong Electron... ^(dong cua so nay de dung^)
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
