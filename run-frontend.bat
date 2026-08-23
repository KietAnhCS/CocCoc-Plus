@echo off
setlocal

set "ELECTRON_BIN=node_modules\electron\dist\electron.exe"

set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "NO_FOOTBALL="
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--no-football" (
    set "NO_FOOTBALL=1"
) else (
    echo [LỖI] Tham số không hiểu: %~1
    echo       Chỉ có: --no-football
    goto :fail
)
shift
goto :parse
:parsed

cd /d "%ROOT%desktop-app" 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy thư mục "%ROOT%desktop-app".
    echo       Tệp .bat này phải nằm ở THƯ MỤC GỐC của kho, cạnh docker-compose.yml.
    goto :fail
)

if not exist "package.json" (
    echo [LỖI] Không thấy package.json trong "%CD%".
    echo       Thư mục desktop-app có vẻ không đầy đủ.
    goto :fail
)

where node >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy Node.js.
    echo       Cài đặt tại https://nodejs.org rồi mở lại cửa sổ này.
    goto :fail
)
for /f "delims=" %%v in ('node --version') do echo Node.js %%v

echo.
echo Đang đồng bộ thư viện theo package.json...
echo.
call npm install --no-audit --no-fund

if not exist "node_modules" (
    echo.
    echo [LỖI] npm install thất bại - vẫn chưa có node_modules.
    echo       Cuộn lên xem thông báo lỗi của npm ở trên.
    goto :fail
)

if not exist "node_modules\zustand" (
    echo.
    echo [LỖI] Thiếu gói zustand dù npm install đã chạy xong.
    echo       Thử xoá node_modules rồi chạy lại tệp này.
    goto :fail
)

if not exist "%ELECTRON_BIN%" (
    echo.
    echo Chưa có bản chạy Electron, đang tải về... ^(mất vài phút^)
    echo.
    call node "node_modules\electron\install.js"
)

if not exist "%ELECTRON_BIN%" (
    echo.
    echo [LỖI] Không tải được bản chạy Electron.
    echo       Kiểm tra kết nối mạng, hoặc chạy tay:
    echo           cd desktop-app ^&^& node node_modules\electron\install.js
    goto :fail
)

rem ===========================================================================
rem FOOTBALL-SERVICE - tiến trình Go riêng ở cổng 8090
rem ===========================================================================
if defined NO_FOOTBALL goto :football_done

set "FB_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8090 .*LISTENING"') do set "FB_PID=%%p"
if defined FB_PID (
    echo football-service : đã chạy sẵn ở cổng 8090
    goto :football_done
)

where docker >nul 2>nul
if errorlevel 1 goto :football_manual
docker info >nul 2>nul
if errorlevel 1 goto :football_manual

rem docker-compose.yml khai báo ADMIN_API_KEY với cú pháp ${...:?}, tức compose
rem dừng ngay nếu biến này trống - kể cả khi ta chỉ bật một dịch vụ không liên
rem quan. Đọc lại từ .env; không có thì đặt một giá trị tạm.
if defined ADMIN_API_KEY goto :fb_key_ok
if not exist "%ENV_FILE%" goto :fb_key_tmp
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY goto :fb_key_ok
:fb_key_tmp
set "ADMIN_API_KEY=khoa-tam-chi-de-compose-doc-duoc-tep"
:fb_key_ok

echo.
echo football-service chưa chạy - đang bật... ^(lần đầu phải build, mất vài phút^)
pushd "%ROOT%"
docker compose up -d football-service
set "FB_ERR=%errorlevel%"
popd
if not "%FB_ERR%"=="0" goto :football_manual

set "FB_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8090 .*LISTENING"') do set "FB_PID=%%p"
if defined FB_PID (
    echo football-service : đã bật ở cổng 8090
    goto :football_done
)
echo.
echo [CẢNH BÁO] Container football-service đã lên nhưng KHÔNG lắng nghe ở cổng
echo            8090 trên máy thật: trong docker-compose.yml nó không ánh xạ
echo            cổng ra ngoài, chỉ với tới được từ trong mạng của compose.
echo            Tab Bóng đá gọi thẳng http://localhost:8090 nên sẽ báo lỗi.
echo            Muốn dùng tab đó khi chạy bằng Docker thì thêm một khối
echo            ports: ["8090:8090"] cho football-service trong docker-compose.yml.
goto :football_done

:football_manual
echo.
echo [CẢNH BÁO] Không tự bật được football-service.
echo            Giao diện VẪN chạy - chỉ riêng tab Bóng đá báo không kết nối.
echo            Bật bằng Docker: docker compose up -d football-service
echo            Chạy tay       : cd backend\services\football-service ^&^& go run ./cmd/server
echo            Bỏ qua hẳn     : run-frontend.bat --no-football

:football_done

rem ===========================================================================
rem API GATEWAY - cửa duy nhất của backend
rem ===========================================================================
set "BE_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8080 .*LISTENING"') do set "BE_PID=%%p"
if defined BE_PID (
    echo api-gateway      : đã chạy sẵn ở cổng 8080
) else (
    echo.
    echo [CẢNH BÁO] Không có gì lắng nghe ở cổng 8080 - api-gateway chưa chạy.
    echo            Giao diện VẪN mở được, nhưng mỗi lần tìm sẽ báo lỗi kết nối.
    echo            Mở một cửa sổ khác và chạy: run-backend.bat
    echo            ^(đợi đến khi nó báo "api-gateway sẵn sàng"^)
)

echo.
call npm run dev
if errorlevel 1 goto :fail

call :restore_cp
endlocal
exit /b 0

:fail
echo.
echo Nhấn phím bất kỳ để đóng...
pause >nul
call :restore_cp
endlocal
exit /b 1

:restore_cp
if defined OLD_CP chcp %OLD_CP% >nul
goto :eof
