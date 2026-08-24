@echo off
setlocal

set "ELECTRON_BIN=node_modules\electron\dist\electron.exe"

set "ROOT=%~dp0"

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

rem --no-football giữ lại cho quen tay nhưng KHÔNG còn tác dụng gì.
rem
rem Bản trước, tab Bóng đá gọi thẳng http://localhost:8090 nên tệp này phải tự
rem bật football-service. Giờ giao diện đi qua Gateway ở cổng 8080 như mọi
rem service khác (xem FOOTBALL_API_BASE trong desktop-app/src/renderer/src/lib/
rem footballApi.ts), nên ở đây chỉ còn đúng một thứ phải sống: api-gateway.
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--no-football" (
    echo [GHI CHÚ] --no-football không còn tác dụng: giao diện gọi bóng đá qua
    echo           Gateway ở cổng 8080, không bật tiến trình riêng nào nữa.
) else (
    echo [LỖI] Tham số không hiểu: %~1
    echo       Tệp này không nhận tham số nào.
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
rem API GATEWAY - cửa duy nhất của backend
rem ===========================================================================
set "BE_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8080 .*LISTENING"') do set "BE_PID=%%p"
if defined BE_PID (
    echo api-gateway      : đã chạy sẵn ở cổng 8080
) else (
    echo.
    echo [CẢNH BÁO] Không có gì lắng nghe ở cổng 8080 - api-gateway chưa chạy.
    echo            Giao diện VẪN mở được, nhưng mỗi lần tìm - và cả tab Bóng đá,
    echo            vốn đi qua tuyến /api/football của Gateway - đều báo lỗi kết nối.
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
