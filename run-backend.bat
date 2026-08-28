@echo off
setlocal

set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

set "MODE=full"
set "USE_DOCKER="
set "FORCE_BUILD="
set "SHOW_WINDOWS="
set "NO_FRONTEND="
set "WITH_CRAWLER="
set "MONITORING="

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
) else if /i "%~1"=="--windows" (
    set "SHOW_WINDOWS=1"
) else if /i "%~1"=="--no-frontend" (
    set "NO_FRONTEND=1"
) else if /i "%~1"=="--crawler" (
    set "WITH_CRAWLER=1"
) else if /i "%~1"=="--no-crawler" (
    REM Bỏ crawler đã là mặc định. Giữ cờ này để lệnh cũ không báo tham số lạ.
    set "WITH_CRAWLER="
) else if /i "%~1"=="--monitoring" (
    set "MONITORING=1"
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

if defined MONITORING if not defined USE_DOCKER (
    echo.
    echo [GHI CHÚ] --monitoring chỉ có tác dụng cùng --docker. Đường chạy jar/binary
    echo           trực tiếp chỉ bật Postgres/Redis/Mongo bằng Docker, không bật
    echo           Prometheus/Grafana/Alertmanager.
)

if defined USE_DOCKER goto :docker_path

where java >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy Java.
    echo       Cần JDK 17 trở lên - cài tại https://adoptium.net rồi mở lại cửa sổ này.
    goto :fail
)

REM history/downloads/settings/football viết bằng Go - chỉ chạy ở chế độ full.
if "%MODE%"=="full" (
    where go >nul 2>nul
    if errorlevel 1 (
        echo [LỖI] Không tìm thấy Go.
        echo       Bốn service history/downloads/settings/football nay viết bằng Go.
        echo       Cài tại https://go.dev/dl rồi mở lại cửa sổ này, hoặc chạy
        echo       run-backend.bat --core để bỏ qua chúng.
        goto :fail
    )
)

set "NEED_BUILD="
if defined FORCE_BUILD set "NEED_BUILD=1"
call :need_jar api-gateway
call :need_jar auth-service
call :need_jar search-service
if "%MODE%"=="full" (
    if defined WITH_CRAWLER call :need_jar crawler-service
    call :need_jar analytics-service
)

if not defined NEED_BUILD goto :build_done
echo.
echo Đang dựng jar cho các service Java... ^(lần đầu mất vài phút^)
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

REM Go build rất nhanh và tự cache - dựng lại mỗi lần cho chắc.
if not "%MODE%"=="full" goto :go_build_done
echo.
echo Đang dựng binary Go ^(football/settings/downloads/history^)...
pushd "%ROOT%backend\go"
if not exist "bin" mkdir "bin"
REM `-o bin` với bin là thư mục sẵn có: Go ghi mỗi binary theo tên package.
go build -o bin ./services/football ./services/settings ./services/downloads ./services/history
set "GO_BUILD_ERR=%errorlevel%"
popd
if not "%GO_BUILD_ERR%"=="0" (
    echo.
    echo [LỖI] go build thất bại. Cuộn lên xem thông báo lỗi ĐẦU TIÊN.
    goto :fail
)
:go_build_done

set "PORT_BUSY="
call :check_port 8080
call :check_port 8081
call :check_port 8082
if "%MODE%"=="full" (
    if defined WITH_CRAWLER call :check_port 8083
    call :check_port 8084
    call :check_port 8085
    call :check_port 8086
    call :check_port 8087
    call :check_port 8090
)
if defined PORT_BUSY (
    echo.
    echo [LỖI] Còn tiến trình cũ đang giữ cổng. Chạy end-backend.bat rồi thử lại.
    goto :fail
)

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

if exist "%ENV_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
        if not defined %%a set "%%a=%%b"
    )
)
if not defined POSTGRES_PASSWORD set "POSTGRES_PASSWORD=vnsearch"

if /i "%APP_CRAWLER_BUS%"=="kafka" (
    set "KAFKA_UP="
    for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":9092 .*LISTENING"') do set "KAFKA_UP=1"
    if not defined KAFKA_UP (
        echo Bus crawl          : .env ghi kafka nhưng cổng 9092 trống - tạm dùng memory
        set "APP_CRAWLER_BUS=memory"
    )
)
if not defined AUTH_DB_PASSWORD      set "AUTH_DB_PASSWORD=%POSTGRES_PASSWORD%"
if not defined DOWNLOADS_DB_PASSWORD set "DOWNLOADS_DB_PASSWORD=%POSTGRES_PASSWORD%"
if not defined SETTINGS_DB_PASSWORD  set "SETTINGS_DB_PASSWORD=%POSTGRES_PASSWORD%"
if not defined FOOTBALL_DB_PASSWORD  set "FOOTBALL_DB_PASSWORD=%POSTGRES_PASSWORD%"

set "AUTH_DB_URL=jdbc:postgresql://localhost:5432/vnsearch_auth"
REM Service Go chấp nhận cả tiền tố `jdbc:` (pg.DSN tự cắt) - giữ nguyên một
REM định dạng URL cho cả Java lẫn Go.
set "DOWNLOADS_DB_URL=jdbc:postgresql://localhost:5432/vnsearch_downloads"
set "SETTINGS_DB_URL=jdbc:postgresql://localhost:5432/vnsearch_settings"
set "APP_STORAGE_POSTGRES_URL=jdbc:postgresql://localhost:5432/vnsearch"
set "FOOTBALL_DB_HOST=localhost"
set "FOOTBALL_DB_PORT=5432"
set "FOOTBALL_DB_NAME=vnsearch"
set "FOOTBALL_DB_USER=vnsearch"
set "MONGO_URI=mongodb://localhost:27017/vnsearch_history"
REM JWKS RS256 - service Go dùng chung claim/issuer/audience với các service Java.
set "AUTH_AUDIENCE=vnsearch-api"

if "%MODE%"=="core" set "MODE_SHOW=RÚT GỌN - api-gateway + auth-service + search-service"
if "%MODE%"=="full" set "MODE_SHOW=ĐẦY ĐỦ - 4 service Java + 4 service Go, KHÔNG có crawler"
if defined WITH_CRAWLER set "MODE_SHOW=ĐẦY ĐỦ - 5 service Java + 4 service Go, có crawler :8083"

echo.
echo === VNSEARCH - CHẠY TRỰC TIẾP ^(jar Java + binary Go^) ===
echo Thư mục            : %CD%
echo Chế độ             : %MODE_SHOW%
echo Khoá quản trị      : %ADMIN_API_KEY:~0,8%...   ^(đầy đủ trong .env^)
echo.

echo === HẠ TẦNG ===
set "INFRA_LIST="
call :need_infra 5432 PostgreSQL postgres
call :need_infra 6379 Redis redis
call :need_infra 27017 MongoDB mongo
if not defined INFRA_LIST goto :infra_ok

echo.
echo Đang bật phần hạ tầng còn thiếu bằng Docker...
where docker >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Thiếu PostgreSQL/Redis/MongoDB mà máy lại không có lệnh docker.
    echo       Cài Docker Desktop tại https://docker.com, hoặc tự dựng ba dịch vụ
    echo       đó ở cổng 5432/6379/27017 rồi chạy lại tệp này.
    goto :fail
)

docker info >nul 2>nul
if not errorlevel 1 goto :infra_up

set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%ProgramW6432%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%LocalAppData%\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" (
    echo [LỖI] Docker engine chưa chạy và không tìm thấy Docker Desktop.exe để mở.
    echo       Mở Docker Desktop bằng tay rồi chạy lại tệp này.
    goto :fail
)

echo   Docker engine chưa chạy - đang mở Docker Desktop...
start "" "%DOCKER_DESKTOP%"
set /a DD_WAIT=0
:wait_docker
ping -n 4 127.0.0.1 >nul
docker info >nul 2>nul
if not errorlevel 1 goto :docker_ready
set /a DD_WAIT+=3
if %DD_WAIT% GEQ 180 (
    echo [LỖI] Đợi 3 phút mà Docker engine vẫn chưa sẵn sàng.
    echo       Mở Docker Desktop và xem nó báo gì, rồi chạy lại tệp này.
    goto :fail
)
echo    ... %DD_WAIT%s
goto :wait_docker
:docker_ready
echo   Docker engine sẵn sàng sau %DD_WAIT%s.

:infra_up
docker compose up -d%INFRA_LIST%
if errorlevel 1 (
    echo.
    echo [LỖI] Không bật được%INFRA_LIST%. Cuộn lên xem dòng lỗi ĐẦU TIÊN.
    goto :fail
)

echo   Đang đợi container báo healthy...
set "INFRA_ERR="
call :wait_health postgres PostgreSQL
call :wait_health redis Redis
call :wait_health mongo MongoDB
if defined INFRA_ERR (
    echo       Xem log: docker compose logs%INFRA_LIST%
    goto :fail
)
echo   Hạ tầng đã sẵn sàng.
:infra_ok
echo.

cd /d "%ROOT%backend"
if not exist "logs" mkdir "logs"

set "LAUNCH_ERR="
call :launch api-gateway 8080
call :launch auth-service 8081
call :launch search-service 8082
if "%MODE%"=="full" (
    if defined WITH_CRAWLER call :launch crawler-service 8083
    call :launch analytics-service 8084
    call :launch_go history 8085
    call :launch_go downloads 8086
    call :launch_go settings 8087
    call :launch_go football 8090
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
echo   Giao diện       mở tự động ở cửa sổ riêng ^(tắt bằng --no-frontend^)
echo   Bóng đá         qua Gateway: http://localhost:8080/api/football/v1/fixtures
echo   Service Go      football/settings/downloads/history - binary ở backend\go\bin
echo   Đo corpus       crawl-stats.bat
echo   Log service     backend\logs\^<ten-service^>.log
echo   TẮT HẾT         end-backend.bat
echo.
echo   PostgreSQL, Redis và MongoDB được tệp này tự bật bằng Docker nếu cổng
echo   5432/6379/27017 còn trống, và Docker Desktop cũng được mở giúp.
echo.
echo   Muốn cả giám sát Prometheus/Grafana thì dùng:
echo       run-backend.bat --docker --monitoring
echo.
if defined NO_FRONTEND goto :fe_done
echo Đang mở giao diện ở cửa sổ riêng...
start "VnSearch giao dien" cmd /k "%ROOT%run-frontend.bat"
:fe_done
call :restore_cp
endlocal
exit /b 0

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

if "%MODE%"=="core" (
    echo.
    echo [GHI CHÚ] --docker luôn bật TOÀN BỘ hệ thống. Tham số --core/--full chỉ
    echo           có tác dụng khi chạy jar trực tiếp.
)

echo.
echo === VNSEARCH - DOCKER COMPOSE ===
set "MON_PROFILE="
set "MON_SHOW=KHÔNG bật - thêm --monitoring nếu cần"
if defined MONITORING (
    set "MON_PROFILE= --profile monitoring"
    set "MON_SHOW=BẬT - Prometheus 9090, Grafana 3000, Alertmanager 9093"
)
echo Chế độ             : TOÀN BỘ - 5 service Java + 4 service Go
echo                      + Postgres/Redis/Mongo
echo Giám sát           : %MON_SHOW%
echo Bộ nhớ             : khoảng 3.5 GB lúc không tải ^(4 service Go nhẹ hơn JVM^)
echo.
docker compose%MON_PROFILE% up -d --build
if errorlevel 1 (
    echo.
    echo [LỖI] docker compose up thất bại. Cuộn lên xem dòng lỗi ĐẦU TIÊN.
    goto :fail
)
echo.
docker compose ps
echo.
echo   Cổng duy nhất   http://localhost:8080
if defined MONITORING (
    echo   Grafana         http://localhost:3000   ^(admin / admin^)
    echo   Prometheus      http://localhost:9090
    echo   Alertmanager    http://localhost:9093
) else (
    echo   Giám sát        không bật - chạy lại kèm --monitoring nếu cần
)
echo   Xem log         docker compose logs -f api-gateway
echo   Thêm Kafka      docker compose --profile kafka up -d
echo   Kafka UI        http://localhost:8091   ^(chỉ khi bật hồ sơ kafka^)
echo   TẮT HẾT         end-backend.bat
echo.
if defined NO_FRONTEND goto :fe_done_docker
echo Đang mở giao diện ở cửa sổ riêng...
start "VnSearch giao dien" cmd /k "%ROOT%run-frontend.bat"
:fe_done_docker
call :restore_cp
endlocal
exit /b 0

:need_infra
set "INFRA_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":%~1 .*LISTENING"') do set "INFRA_PID=%%p"
if defined INFRA_PID (
    echo   %~2 :%~1 - đã chạy
    goto :eof
)
echo   %~2 :%~1 - chưa chạy
set "INFRA_LIST=%INFRA_LIST% %~3"
goto :eof

:wait_health
set /a WH_WAIT=0
:wait_health_loop
set "WH_STATE="
for /f "delims=" %%h in ('docker inspect -f "{{.State.Health.Status}}" vnsearch-%~1 2^>nul') do set "WH_STATE=%%h"
if not defined WH_STATE goto :eof
if "%WH_STATE%"=="healthy" goto :eof
ping -n 3 127.0.0.1 >nul
set /a WH_WAIT+=2
if %WH_WAIT% GEQ 150 (
    echo   [LỖI] %~2 vẫn ở trạng thái "%WH_STATE%" sau 150 giây.
    set "INFRA_ERR=1"
    goto :eof
)
goto :wait_health_loop

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
if defined SHOW_WINDOWS (
    start "VnSearch %~1 :%~2" cmd /k java -jar "services\%~1\target\%~1-0.0.1-SNAPSHOT.jar"
    echo   %~1 :%~2 - cửa sổ console riêng
    goto :eof
)
powershell -NoProfile -Command "Start-Process -FilePath java -ArgumentList @('-jar', 'services\%~1\target\%~1-0.0.1-SNAPSHOT.jar') -WindowStyle Hidden -RedirectStandardOutput 'logs\%~1.log' -RedirectStandardError 'logs\%~1.err.log'"
echo   %~1 :%~2 - chạy ngầm, log: backend\logs\%~1.log
goto :eof

REM %~1 = tên thư mục Go (football/settings/downloads/history), %~2 = cổng.
REM Tên log giữ hậu tố "-service" cho khớp với end-backend.bat và thói quen cũ.
:launch_go
if not exist "go\bin\%~1.exe" (
    echo [LỖI] Chưa có "backend\go\bin\%~1.exe".
    echo       Chạy lại với tham số --build.
    set "LAUNCH_ERR=1"
    goto :eof
)
set "SERVER_PORT=%~2"
if defined SHOW_WINDOWS (
    start "VnSearch %~1-service :%~2" cmd /k "set SERVER_PORT=%~2&& go\bin\%~1.exe"
    echo   %~1-service :%~2 - cửa sổ console riêng
    set "SERVER_PORT="
    goto :eof
)
powershell -NoProfile -Command "Start-Process -FilePath 'go\bin\%~1.exe' -WindowStyle Hidden -RedirectStandardOutput 'logs\%~1-service.log' -RedirectStandardError 'logs\%~1-service.err.log'"
echo   %~1-service :%~2 - chạy ngầm, log: backend\logs\%~1-service.log
set "SERVER_PORT="
goto :eof

:usage
echo.
echo   run-backend.bat            4 service Java ^(jar^) + 4 service Go ^(binary^), KHÔNG
echo                              có crawler-service - xem --crawler bên dưới
echo                              + mở luôn giao diện
echo   run-backend.bat --core     chỉ api-gateway + auth-service + search-service
echo                              ^(bỏ qua 4 service Go, tab Bóng đá sẽ trống^)
echo   run-backend.bat --no-frontend
echo                              chỉ chạy backend, không mở giao diện
echo   run-backend.bat --build    dựng lại jar Java trước khi chạy
echo                              ^(binary Go luôn được dựng lại, go build rất nhanh^)
echo   run-backend.bat --windows  mở một cửa sổ console cho mỗi service thay vì
echo                              chạy ngầm ^(8 cửa sổ, 9 nếu kèm --crawler^)
echo   run-backend.bat --docker   chạy bằng docker compose - bật toàn bộ hệ thống
echo                              trong container, nên --core/--full không đổi được
echo                              gì ở đường này
echo   run-backend.bat --crawler  thêm crawler-service :8083. Mặc định KHÔNG bật: nó
echo                              nạp sẵn chính bản chỉ mục mà search-service đã nạp
echo                              - đo được 2 GB cho đúng một controller quản trị, và
echo                              đó là tiến trình đã làm máy hết RAM. Tìm kiếm không
echo                              cần nó. Không bật thì mất /api/admin/** và bảng
echo                              điều khiển của analytics-service; run-crawl.bat vẫn
echo                              chạy được vì KHÔNG dùng tới nó.
echo   run-backend.bat --monitoring
echo                              thêm Prometheus/Grafana/Alertmanager ^(hồ sơ
echo                              `monitoring` của compose^). Mặc định KHÔNG bật:
echo                              ba container đó tốn khoảng 544 MB trần RAM mà
echo                              phần lớn thời gian không ai mở Grafana.
echo                              Chỉ có tác dụng cùng --docker.
echo.
echo   Chế độ trực tiếp chạy các service NGẦM, log đổ vào backend\logs\^<ten^>.log.
echo   Cần Java ^(JDK 17+^) và Go ^(1.24+^). PostgreSQL, Redis và MongoDB tự bật bằng
echo   Docker khi cổng còn trống, kể cả việc mở Docker Desktop hộ.
echo.
echo   Tắt hết: end-backend.bat
echo.
echo   Biến môi trường:
echo     ADMIN_API_KEY             khoá cho /api/admin/**, tối thiểu 16 ký tự.
echo                               Không đặt thì lấy từ .env, không có nữa thì tự sinh.
echo     BOOTSTRAP_ADMIN_PASSWORD  mật khẩu tài khoản quản trị đầu tiên của
echo                               auth-service. Thiếu thì tự sinh và ghi vào .env.
echo.
echo   Bảng cổng ^(Java 8080-8084, Go 8085-8087 + 8090^):
echo     8080 api-gateway    8081 auth-service      8082 search-service
echo     8083 crawler        8084 analytics         8085 history  ^(Go^)
echo          ^(chỉ khi có --crawler^)
echo     8086 downloads ^(Go^) 8087 settings ^(Go^)    8090 football ^(Go^)
echo.
echo   Chỉ khi chạy bằng --docker --monitoring:
echo     3000 Grafana        9090 Prometheus        9093 Alertmanager
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
