@echo off
rem KHONG viet tieng Viet co dau trong file .bat: cmd.exe phan tich file theo
rem byte offset, ky tu da byte lam lech con tro doc va cat vun cac dong lenh
rem phia sau. Ly do day du xem trong run-crawl.bat.
setlocal

rem ===========================================================================
rem   run-frontend.bat                mo giao dien, tu bat football-service
rem   run-frontend.bat --no-football  bo qua phan football-service
rem ===========================================================================

set "ELECTRON_BIN=node_modules\electron\dist\electron.exe"

rem Chot duong dan goc TRUOC khi cd: phan football ben duoi can chay
rem `docker compose` o thu muc goc, con phan con lai lam viec trong browser-app.
set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

set "NO_FOOTBALL="
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--no-football" (
    set "NO_FOOTBALL=1"
) else (
    echo [LOI] Tham so khong hieu: %~1
    echo       Chi co: --no-football
    goto :fail
)
shift
goto :parse
:parsed

rem `%ROOT%` chu KHONG phai `%~dp0`: sau vong lap doc tham so o tren, `shift` da
rem dich ca %0, nen `%~dp0` khong con la thu muc chua file .bat nua.
cd /d "%ROOT%browser-app" 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay thu muc "%ROOT%browser-app".
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

rem ===========================================================================
rem FOOTBALL-SERVICE
rem ===========================================================================
rem Phan The thao cua giao dien goi thang cong 8090, mot tien trinh KHAC han
rem backend tim kiem o 8080. Khong bat thi tab Bong da chi hien mot bang bao
rem loi - va do la loi de trach nham nhat trong ca ung dung, vi moi thu con lai
rem van chay binh thuong.
if defined NO_FOOTBALL goto :football_done

set "FB_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8090 .*LISTENING"') do set "FB_PID=%%p"
if defined FB_PID (
    echo football-service : da chay san o cong 8090
    goto :football_done
)

where docker >nul 2>nul
if errorlevel 1 goto :football_manual
docker info >nul 2>nul
if errorlevel 1 goto :football_manual

rem docker-compose.yml khai bao ADMIN_API_KEY voi cu phap `${...:?}`, tuc
rem compose DUNG NGAY neu bien nay trong - ke ca khi ta chi bat mot dich vu
rem khong lien quan. Doc lai tu .env; khong co thi dat mot gia tri tam, vi
rem football-service khong dung toi khoa nay.
if defined ADMIN_API_KEY goto :fb_key_ok
if not exist "%ENV_FILE%" goto :fb_key_tmp
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY goto :fb_key_ok
:fb_key_tmp
set "ADMIN_API_KEY=khoa-tam-chi-de-compose-doc-duoc-file"
:fb_key_ok

echo.
echo football-service chua chay - dang bat... ^(lan dau phai build, mat vai phut^)
pushd "%ROOT%"
docker compose --profile football up -d football-service
set "FB_ERR=%errorlevel%"
popd
if not "%FB_ERR%"=="0" goto :football_manual
echo football-service : da bat o cong 8090
goto :football_done

:football_manual
echo.
echo [CANH BAO] Khong tu bat duoc football-service.
echo            Giao dien VAN chay - chi rieng tab Bong da bao khong ket noi.
echo            Bat tay: docker compose --profile football up -d
echo            Bo qua han: run-frontend.bat --no-football

:football_done

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
