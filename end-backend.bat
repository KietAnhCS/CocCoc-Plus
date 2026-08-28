@echo off
setlocal

set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "LOCAL_ONLY="
set "KEEP_DOCKER="
set "STOP_ONLY="
set "WIPE="
set "KILL_WSL="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--local" (
    set "LOCAL_ONLY=1"
) else if /i "%~1"=="--keep-docker" (
    set "KEEP_DOCKER=1"
) else if /i "%~1"=="--stop" (
    set "STOP_ONLY=1"
    set "KEEP_DOCKER=1"
) else if /i "%~1"=="--wipe" (
    set "WIPE=1"
) else if /i "%~1"=="--wsl" (
    set "KILL_WSL=1"
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

echo.
echo === TẮT VNSEARCH ===

set "RAM_BEFORE="
for /f "delims=" %%m in ('powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,2)"') do set "RAM_BEFORE=%%m"
if defined RAM_BEFORE echo RAM trống lúc bắt đầu: %RAM_BEFORE% GB

echo.
echo Đang dừng các service chạy trực tiếp ^(jar Java + binary Go^)...
set "KILLED="
call :kill_port 8080 api-gateway
call :kill_port 8081 auth-service
call :kill_port 8082 search-service
call :kill_port 8083 crawler-service
call :kill_port 8084 analytics-service
call :kill_port 8085 "history-service (Go)"
call :kill_port 8086 "downloads-service (Go)"
call :kill_port 8087 "settings-service (Go)"
call :kill_port 8090 "football-service (Go)"
if not defined KILLED echo   Không có tiến trình nào giữ cổng 8080-8087 và 8090.

if defined LOCAL_ONLY goto :report

where docker >nul 2>nul
if errorlevel 1 (
    echo.
    echo Không có lệnh docker - bỏ qua phần container.
    goto :report
)

docker info >nul 2>nul
if errorlevel 1 (
    echo.
    echo Docker engine không chạy - không có container nào đang sống.
    goto :shutdown_desktop
)

if defined ADMIN_API_KEY goto :key_ok
if not exist "%ENV_FILE%" goto :key_placeholder
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY goto :key_ok
:key_placeholder
set "ADMIN_API_KEY=khoa-tam-chi-de-compose-doc-duoc-tep"
:key_ok

set "PROFILES=--profile kafka"

if defined STOP_ONLY (
    echo.
    echo Đang dừng container, vẫn giữ lại để bật lại cho nhanh...
    docker compose %PROFILES% stop
    if errorlevel 1 goto :compose_failed
    echo Container đã dừng. Bật lại: run-backend.bat --docker
    goto :report
)

if defined WIPE goto :wipe

echo.
echo Đang hạ container...
docker compose %PROFILES% down --remove-orphans
if errorlevel 1 goto :compose_failed
echo Đã hạ xong. Volume dữ liệu vẫn còn.
goto :shutdown_desktop

:wipe
echo.
echo [CẢNH BÁO] --wipe sẽ XOÁ các volume:
echo              postgres-data      toàn bộ CSDL tài liệu đã crawl
echo              mongo-data         dữ liệu lịch sử và tải xuống
echo              kafka-data         log các topic
echo              prometheus-data    lịch sử số liệu đo
echo              grafana-data       người dùng và thiết lập Grafana
echo            Không thể hoàn tác. Corpus JSON trong backend\data KHÔNG bị
echo            ảnh hưởng vì nó nằm trên máy thật, không phải volume.
echo.
set "CONFIRM="
set /p "CONFIRM=Gõ đúng chữ XOA rồi Enter để xác nhận: "
if /i not "%CONFIRM%"=="XOA" (
    echo Đã huỷ - không xoá gì.
    goto :fail
)
echo.
echo Đang hạ container và xoá volume...
docker compose %PROFILES% down --volumes --remove-orphans
if errorlevel 1 goto :compose_failed
echo Đã hạ xong và đã XOÁ volume. Lần bật lại sẽ khởi tạo CSDL rỗng.

:shutdown_desktop
if defined KEEP_DOCKER goto :report

set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%ProgramW6432%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%LocalAppData%\Docker\Docker Desktop.exe"

tasklist /FI "IMAGENAME eq Docker Desktop.exe" /NH | findstr /i /c:"Docker Desktop.exe" >nul
if errorlevel 1 (
    echo.
    echo Docker Desktop không chạy - không có gì để đóng.
    goto :report
)

if not exist "%DOCKER_DESKTOP%" (
    echo.
    echo [CẢNH BÁO] Không tìm thấy Docker Desktop.exe để đóng bằng lệnh.
    echo            Đóng bằng tay ở khay hệ thống: Quit Docker Desktop.
    goto :report
)

echo.
echo Đang đóng Docker Desktop để trả lại RAM của máy ảo...
start "" "%DOCKER_DESKTOP%" -Shutdown

set /a DD_WAIT=0
:wait_dd
ping -n 3 127.0.0.1 >nul
tasklist /FI "IMAGENAME eq Docker Desktop.exe" /NH | findstr /i /c:"Docker Desktop.exe" >nul
if errorlevel 1 goto :dd_done
set /a DD_WAIT+=2
if %DD_WAIT% GEQ 90 (
    echo [CẢNH BÁO] Đợi 90 giây mà Docker Desktop chưa đóng hẳn.
    echo            Cứ để nó tự đóng nốt, hoặc đóng tay ở khay hệ thống.
    goto :report
)
goto :wait_dd

:dd_done
echo Docker Desktop đã đóng sau %DD_WAIT%s.

taskkill /F /IM "Docker Desktop.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.backend.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.build.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.dev-envs.exe" /T >nul 2>nul

where wsl >nul 2>nul
if not errorlevel 1 (
    echo Đang tắt máy ảo con của Docker trong WSL2 ^(distro docker-desktop^)...
    wsl --terminate docker-desktop >nul 2>nul
    echo Đã tắt. VmmemWSL vẫn còn nhưng chỉ giữ mức nền, không còn RAM của Docker.
)

if defined KILL_WSL (
    echo.
    echo Đang tắt toàn bộ máy ảo WSL2...
    wsl --shutdown
    echo Đã tắt WSL2. Lưu ý: lệnh này tắt MỌI distro WSL, không riêng của Docker.
)

:report
ping -n 4 127.0.0.1 >nul
set "RAM_AFTER="
for /f "delims=" %%m in ('powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,2)"') do set "RAM_AFTER=%%m"

echo.
echo === XONG ===
if defined RAM_BEFORE if defined RAM_AFTER (
    echo RAM trống: %RAM_BEFORE% GB  -^>  %RAM_AFTER% GB
)
echo Bật lại hệ thống: run-backend.bat
echo.

call :restore_cp
endlocal
exit /b 0

:kill_port
set "PORT_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":%~1 .*LISTENING"') do set "PORT_PID=%%p"
if not defined PORT_PID goto :eof

REM Ở chế độ Docker, cổng do MỘT tiến trình của chính Docker Desktop giữ
REM ^(com.docker.backend / vpnkit / Docker Desktop.exe^). taskkill vào đó là
REM giết luôn Docker engine, và `docker compose down` bên dưới không còn chạy
REM được. Bỏ qua - phần hạ container sẽ lo đúng cách.
set "PORT_IMG="
for /f "tokens=1 delims=," %%i in ('tasklist /FI "PID eq %PORT_PID%" /FO CSV /NH 2^>nul') do set "PORT_IMG=%%~i"
echo %PORT_IMG% | findstr /i /c:"docker" /c:"vpnkit" /c:"wslrelay" /c:"com.docker" >nul
if not errorlevel 1 (
    echo   %~2 :%~1 - PID %PORT_PID% là tiến trình Docker ^(%PORT_IMG%^), bỏ qua.
    goto :eof
)

set "KILLED=1"
echo   %~2 :%~1 - PID %PORT_PID%, đang tắt...
taskkill /PID %PORT_PID% /T /F >nul 2>nul
if errorlevel 1 (
    echo   [CẢNH BÁO] Không tắt được PID %PORT_PID%. Nếu đó là container Docker
    echo              thì phần `docker compose down` bên dưới sẽ lo.
)
goto :eof

:compose_failed
echo.
echo [LỖI] Lệnh docker compose thất bại. Cuộn lên xem thông báo ở trên.
echo       Cách mạnh tay hơn, đóng theo TÊN container:
echo           docker rm -f vnsearch-gateway vnsearch-auth vnsearch-search
echo           docker rm -f vnsearch-crawler vnsearch-analytics vnsearch-history
echo           docker rm -f vnsearch-downloads vnsearch-settings vnsearch-football
echo           docker rm -f vnsearch-postgres vnsearch-redis vnsearch-mongo
echo           docker rm -f vnsearch-kafka vnsearch-kafka-ui vnsearch-kafka-exporter
echo           docker rm -f vnsearch-prometheus vnsearch-grafana vnsearch-alertmanager
goto :fail

:usage
echo.
echo   end-backend.bat                dừng tiến trình jar ^(cổng 8080-8087, 8090^), hạ
echo                                  container, tắt Docker Desktop và tắt luôn
echo                                  distro WSL2 của Docker để RAM về mức nền
echo   end-backend.bat --local        chỉ dừng tiến trình jar, không đụng Docker
echo   end-backend.bat --keep-docker  hạ container nhưng để Docker Desktop chạy
echo   end-backend.bat --stop         chỉ dừng container, KHÔNG xoá - bật lại nhanh
echo   end-backend.bat --wipe         hạ container VÀ XOÁ volume - MẤT DỮ LIỆU
echo   end-backend.bat --wsl          tắt CẢ máy ảo WSL2 ^(mọi distro, không riêng
echo                                  Docker^) sau khi tắt Docker
echo.
echo   Bật lại: run-backend.bat
echo.
call :restore_cp
endlocal
exit /b 0

:usage_fail
echo   Chạy "end-backend.bat --help" để xem các tham số hợp lệ.
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
