@echo off
setlocal

set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

set "MODE=core"
set "USE_DOCKER="
set "FORCE_BUILD="

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--full" (
    set "MODE=full"
) else if /i "%~1"=="--core" (
    set "MODE=core"
) else if /i "%~1"=="--docker" (
    set "USE_DOCKER=1"
) else if /i "%~1"=="--build" (
    set "FORCE_BUILD=1"
) else (
    echo [LỖI] Tham số không hiểu: %~1
    echo.
    goto :usage_fail
)
shift
goto :parse
:parsed

cd /d "%ROOT%" 2>nul
if not exist "docker-compose.yml" (
    echo [LỖI] Không thấy docker-compose.yml trong "%CD%".
    echo       Tệp .bat này phải nằm ở THƯ MỤC GỐC của kho.
    goto :fail
)
if not exist "backend\pom.xml" (
    echo [LỖI] Không thấy "backend\pom.xml".
    echo       Thư mục backend của kho có vẻ không đầy đủ.
    goto :fail
)

rem === Khoá quản trị ===
if defined ADMIN_API_KEY goto :key_ok
if not exist "%ENV_FILE%" goto :key_new
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY (
    echo Khoá quản trị      : đọc từ "%ENV_FILE%"
    goto :key_ok
)

:key_new
echo Khoá quản trị      : chưa có, đang sinh khoá mới...
for /f "delims=" %%k in ('powershell -NoProfile -Command "$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace [char]45, [string]::Empty).ToLower()"') do set "ADMIN_API_KEY=%%k"
if not defined ADMIN_API_KEY (
    echo [LỖI] Không sinh được khoá vì không gọi được PowerShell.
    echo       Đặt tay rồi chạy lại:
    echo           set ADMIN_API_KEY=mot-chuoi-bat-ky-tu-16-ky-tu-tro-len
    goto :fail
)
if not exist "%ENV_FILE%" (
    >"%ENV_FILE%" echo # Sinh tự động bởi run-backend.bat. KHÔNG commit - .gitignore đã chặn.
)
>>"%ENV_FILE%" echo ADMIN_API_KEY=%ADMIN_API_KEY%
echo                      đã ghi vào "%ENV_FILE%"

:key_ok
set "KEY_PROBE=%ADMIN_API_KEY:~15,1%"
if not defined KEY_PROBE (
    echo [LỖI] ADMIN_API_KEY ngắn hơn 16 ký tự nên ServiceSecurityConfig sẽ từ chối khởi động.
    echo       Sửa dòng ADMIN_API_KEY trong "%ENV_FILE%", hoặc xoá hẳn dòng đó
    echo       để tệp này sinh lại khoá mới.
    goto :fail
)

rem === Mật khẩu quản trị mồi của auth-service ===
if defined BOOTSTRAP_ADMIN_PASSWORD goto :pw_ok
if not exist "%ENV_FILE%" goto :pw_new
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="BOOTSTRAP_ADMIN_PASSWORD" set "BOOTSTRAP_ADMIN_PASSWORD=%%b"
)
if defined BOOTSTRAP_ADMIN_PASSWORD (
    echo Tài khoản quản trị : đọc từ "%ENV_FILE%"
    goto :pw_ok
)

:pw_new
for /f "delims=" %%k in ('powershell -NoProfile -Command "$b = New-Object byte[] 12; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace [char]45, [string]::Empty).ToLower()"') do set "BOOTSTRAP_ADMIN_PASSWORD=%%k"
if not defined BOOTSTRAP_ADMIN_PASSWORD (
    echo [LỖI] Không sinh được BOOTSTRAP_ADMIN_PASSWORD.
    echo       Đặt tay rồi chạy lại: set BOOTSTRAP_ADMIN_PASSWORD=...
    goto :fail
)
if not exist "%ENV_FILE%" (
    >"%ENV_FILE%" echo # Sinh tự động bởi run-backend.bat. KHÔNG commit - .gitignore đã chặn.
)
>>"%ENV_FILE%" echo BOOTSTRAP_ADMIN_USERNAME=admin
>>"%ENV_FILE%" echo BOOTSTRAP_ADMIN_PASSWORD=%BOOTSTRAP_ADMIN_PASSWORD%
echo Tài khoản quản trị : admin / %BOOTSTRAP_ADMIN_PASSWORD%   ^(đã ghi vào .env^)

:pw_ok
if not defined BOOTSTRAP_ADMIN_USERNAME set "BOOTSTRAP_ADMIN_USERNAME=admin"

if defined USE_DOCKER goto :docker_path

rem ===========================================================================
rem CHẠY TRỰC TIẾP BẰNG JAR - không cần Docker
rem ===========================================================================
where java >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy Java.
    echo       Cần JDK 17 trở lên - cài tại https://adoptium.net rồi mở lại cửa sổ này.
    goto :fail
)

set "NEED_BUILD="
if defined FORCE_BUILD set "NEED_BUILD=1"
call :need_jar api-gateway
call :need_jar auth-service
call :need_jar search-service
if "%MODE%"=="full" (
    call :need_jar crawler-service
    call :need_jar analytics-service
    call :need_jar history-service
    call :need_jar downloads-service
    call :need_jar settings-service
)

if not defined NEED_BUILD goto :build_done
echo.
echo Đang dựng jar cho toàn bộ reactor... ^(lần đầu mất vài phút^)
echo.
pushd "%ROOT%backend"
call mvnw.cmd -B clean package -DskipTests
set "BUILD_ERR=%errorlevel%"
popd
if not "%BUILD_ERR%"=="0" (
    echo.
    echo [LỖI] Dựng Maven thất bại. Cuộn lên xem thông báo lỗi ĐẦU TIÊN.
    goto :fail
)
:build_done

set "PORT_BUSY="
call :check_port 8080
call :check_port 8081
call :check_port 8082
if "%MODE%"=="full" (
    call :check_port 8083
    call :check_port 8084
    call :check_port 8085
    call :check_port 8086
    call :check_port 8087
)
if defined PORT_BUSY (
    echo.
    echo [LỖI] Còn tiến trình cũ đang giữ cổng. Chạy end-backend.bat rồi thử lại.
    goto :fail
)

rem Trong mạng Docker các service gọi nhau bằng tên container. Chạy trên máy
rem thật thì mọi địa chỉ đó phải trỏ về localhost.
set "AUTH_SERVICE_URL=http://localhost:8081"
set "SEARCH_SERVICE_URL=http://localhost:8082"
set "CRAWLER_SERVICE_URL=http://localhost:8083"
set "ANALYTICS_SERVICE_URL=http://localhost:8084"
set "HISTORY_SERVICE_URL=http://localhost:8085"
set "DOWNLOADS_SERVICE_URL=http://localhost:8086"
set "SETTINGS_SERVICE_URL=http://localhost:8087"
set "FOOTBALL_SERVICE_URL=http://localhost:8090"
set "AUTH_ISSUER_URI=http://localhost:8081"
set "AUTH_JWKS_URI=http://localhost:8081/oauth2/jwks"
set "REDIS_HOST=localhost"

if "%MODE%"=="core" set "MODE_SHOW=RÚT GỌN - api-gateway + auth-service + search-service"
if "%MODE%"=="full" set "MODE_SHOW=ĐẦY ĐỦ - 8 service Java"

echo.
echo === VNSEARCH - CHẠY TRỰC TIẾP BẰNG JAR ===
echo Thư mục            : %CD%
echo Chế độ             : %MODE_SHOW%
echo Khoá quản trị      : %ADMIN_API_KEY:~0,8%...   ^(đầy đủ trong .env^)
echo.

cd /d "%ROOT%backend"

set "LAUNCH_ERR="
call :launch api-gateway 8080
call :launch auth-service 8081
call :launch search-service 8082
if "%MODE%"=="full" (
    call :launch crawler-service 8083
    call :launch analytics-service 8084
    call :launch history-service 8085
    call :launch downloads-service 8086
    call :launch settings-service 8087
)
if defined LAUNCH_ERR goto :fail

echo.
echo Đang đợi api-gateway trả lời...
set /a HEALTH_WAIT=0
:wait_gw
ping -n 4 127.0.0.1 >nul
set "GW_UP="
for /f "delims=" %%s in ('powershell -NoProfile -Command "try { if ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://localhost:8080/actuator/health).StatusCode -eq 200) { 'UP' } } catch { }"') do set "GW_UP=%%s"
if "%GW_UP%"=="UP" goto :gw_ready
set /a HEALTH_WAIT+=3
if %HEALTH_WAIT% GEQ 180 (
    echo [CẢNH BÁO] Đợi 3 phút mà api-gateway vẫn chưa trả lời.
    echo            Xem cửa sổ console của từng service để biết nó kẹt ở đâu.
    goto :gw_done
)
echo    ... %HEALTH_WAIT%s
goto :wait_gw

:gw_ready
echo api-gateway sẵn sàng sau %HEALTH_WAIT%s.
:gw_done

echo.
echo === ĐỊA CHỈ ===
echo   Cổng duy nhất   http://localhost:8080
echo   Kiểm tra sống   http://localhost:8080/actuator/health
echo   Thử tìm kiếm    http://localhost:8080/api/search?q=ha+noi
echo   Swagger UI      http://localhost:8080/swagger-ui.html
echo.
echo   Giao diện       chạy run-frontend.bat ở một cửa sổ khác
echo   Bóng đá         viết bằng Go, bật riêng: docker compose up -d football-service
echo   Đo corpus       crawl-stats.bat
echo   TẮT HẾT         end-backend.bat
echo.
echo   Chưa có Redis ở cổng 6379 thì các tuyến có giới hạn tần suất của Gateway
echo   sẽ báo lỗi. Bật riêng Redis: docker compose up -d redis
echo.
call :restore_cp
endlocal
exit /b 0

rem ===========================================================================
rem CHẠY BẰNG DOCKER COMPOSE
rem ===========================================================================
:docker_path
where docker >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy lệnh docker.
    echo       Cài Docker Desktop, hoặc bỏ tham số --docker để chạy jar trực tiếp.
    goto :fail
)
docker info >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Docker engine chưa chạy. Mở Docker Desktop rồi thử lại.
    goto :fail
)

set "PROFILES="
if "%MODE%"=="full" set "PROFILES=--profile full"

echo.
echo === VNSEARCH - DOCKER COMPOSE ===
if "%MODE%"=="full" echo Chế độ             : ĐẦY ĐỦ - profile full, khoảng 5,4 GB RAM
if "%MODE%"=="core" echo Chế độ             : RÚT GỌN - hồ sơ mặc định, khoảng 2,6 GB RAM
echo.
docker compose %PROFILES% up -d --build
if errorlevel 1 (
    echo.
    echo [LỖI] docker compose up thất bại. Cuộn lên xem dòng lỗi ĐẦU TIÊN.
    goto :fail
)
echo.
docker compose %PROFILES% ps
echo.
echo   Cổng duy nhất   http://localhost:8080
echo   Xem log         docker compose logs -f api-gateway
echo   TẮT HẾT         end-backend.bat
echo.
call :restore_cp
endlocal
exit /b 0

rem ===========================================================================
:need_jar
if not exist "%ROOT%backend\services\%~1\target\%~1-0.0.1-SNAPSHOT.jar" set "NEED_BUILD=1"
goto :eof

:check_port
set "PORT_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":%~1 .*LISTENING"') do set "PORT_PID=%%p"
if not defined PORT_PID goto :eof
echo [LỖI] Cổng %~1 đang bị tiến trình PID %PORT_PID% chiếm.
set "PORT_BUSY=1"
goto :eof

:launch
if not exist "services\%~1\target\%~1-0.0.1-SNAPSHOT.jar" (
    echo [LỖI] Chưa có "services\%~1\target\%~1-0.0.1-SNAPSHOT.jar".
    echo       Chạy lại với tham số --build.
    set "LAUNCH_ERR=1"
    goto :eof
)
start "VnSearch %~1 :%~2" cmd /k java -jar "services\%~1\target\%~1-0.0.1-SNAPSHOT.jar"
echo   %~1 :%~2 - đã mở cửa sổ console riêng
goto :eof

:usage
echo.
echo   run-backend.bat            hồ sơ RÚT GỌN: api-gateway + auth-service + search-service
echo   run-backend.bat --full     cả 8 service Java: thêm crawler, analytics, history,
echo                              downloads, settings
echo   run-backend.bat --build    dựng lại jar trước khi chạy
echo   run-backend.bat --docker   chạy bằng docker compose thay vì jar trực tiếp,
echo                              ghép được với --full
echo.
echo   Mỗi service mở một cửa sổ console riêng để đọc log.
echo   Tắt hết: end-backend.bat
echo.
echo   Biến môi trường:
echo     ADMIN_API_KEY             khoá cho /api/admin/**, tối thiểu 16 ký tự.
echo                               Không đặt thì lấy từ .env, không có nữa thì tự sinh.
echo     BOOTSTRAP_ADMIN_PASSWORD  mật khẩu tài khoản quản trị đầu tiên của
echo                               auth-service. Thiếu thì tự sinh và ghi vào .env.
echo.
echo   Bảng cổng:
echo     8080 api-gateway    8081 auth-service      8082 search-service
echo     8083 crawler        8084 analytics         8085 history
echo     8086 downloads      8087 settings          8090 football
echo.
call :restore_cp
endlocal
exit /b 0

:usage_fail
echo   Chạy "run-backend.bat --help" để xem các tham số hợp lệ.
echo.
echo Nhấn phím bất kỳ để đóng...
pause >nul
call :restore_cp
endlocal
exit /b 1

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
