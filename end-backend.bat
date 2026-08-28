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
set "NO_WSL="

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
    set "NO_WSL="
) else if /i "%~1"=="--no-wsl" (
    set "NO_WSL=1"
    set "KILL_WSL="
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

set /a TOTAL=3
if not defined LOCAL_ONLY set /a TOTAL+=2
if not defined LOCAL_ONLY if not defined KEEP_DOCKER set /a TOTAL+=1
if not defined NO_WSL set /a TOTAL+=1
set /a STEP=0

echo.
echo === TẮT VNSEARCH ===

set "RAM_BEFORE="
for /f "delims=" %%m in ('powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,2)"') do set "RAM_BEFORE=%%m"
if defined RAM_BEFORE echo RAM trống lúc bắt đầu: %RAM_BEFORE% GB

call :step "Dừng service chạy trực tiếp (jar Java + binary Go)"
set "KILLED="
call :kill_port 8080 api-gateway
call :kill_port 8081 auth-service
call :kill_port 8082 search-service
call :kill_port 8083 "crawler-service (chỉ khi --crawler)"
call :kill_port 8084 analytics-service
call :kill_port 8085 "history-service (Go)"
call :kill_port 8086 "downloads-service (Go)"
call :kill_port 8087 "settings-service (Go)"
call :kill_port 8090 "football-service (Go)"
if not defined KILLED echo   Không có tiến trình nào giữ cổng 8080-8087 và 8090.

if defined LOCAL_ONLY goto :wsl_step

call :step "Kiểm tra Docker engine"

where docker >nul 2>nul
if not errorlevel 1 goto :docker_found
echo   Máy không có lệnh docker - bỏ qua toàn bộ phần container.
call :step "Hạ container (bỏ qua - không có docker)"
if not defined KEEP_DOCKER call :step "Đóng Docker Desktop (bỏ qua - không có docker)"
goto :wsl_step
:docker_found

docker info >nul 2>nul
if not errorlevel 1 goto :engine_up
echo   Docker engine không chạy - không có container nào đang sống.
call :step "Hạ container (bỏ qua - engine đã tắt)"
goto :shutdown_desktop
:engine_up

if defined ADMIN_API_KEY goto :key_ok
if not exist "%ENV_FILE%" goto :key_placeholder
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY goto :key_ok
:key_placeholder
set "ADMIN_API_KEY=khoa-tam-chi-de-compose-doc-duoc-tep"
:key_ok

set "PROFILES=--profile kafka --profile monitoring"

call :step "Dừng/hạ container Docker"

if defined STOP_ONLY (
    echo.
    echo Đang dừng container, vẫn giữ lại để bật lại cho nhanh...
    docker compose %PROFILES% stop
    if errorlevel 1 goto :compose_failed
    echo Container đã dừng. Bật lại: run-backend.bat --docker
    goto :wsl_step
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
if defined KEEP_DOCKER goto :wsl_step

call :step "Đóng Docker Desktop và trả RAM của máy ảo"

set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%ProgramW6432%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%LocalAppData%\Docker\Docker Desktop.exe"

tasklist /FI "IMAGENAME eq Docker Desktop.exe" /NH | findstr /i /c:"Docker Desktop.exe" >nul
if errorlevel 1 (
    echo.
    echo Docker Desktop không chạy - không có gì để đóng.
    goto :wsl_step
)

if not exist "%DOCKER_DESKTOP%" (
    echo.
    echo [CẢNH BÁO] Không tìm thấy Docker Desktop.exe để đóng bằng lệnh.
    echo            Đóng bằng tay ở khay hệ thống: Quit Docker Desktop.
    goto :wsl_step
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
    goto :wsl_step
)
goto :wait_dd

:dd_done
echo Docker Desktop đã đóng sau %DD_WAIT%s.

taskkill /F /IM "Docker Desktop.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.backend.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.build.exe" /T >nul 2>nul
taskkill /F /IM "com.docker.dev-envs.exe" /T >nul 2>nul

:wsl_step
REM Bước này phải nằm NGOÀI khối tắt Docker Desktop. Trước đây nó nằm ở cuối
REM khối đó, sau bảy nhánh `goto :report` — nên `--wsl` chỉ chạy đúng một
REM đường duy nhất: Docker Desktop đang mở VÀ đóng xong trong 90 giây. Mọi
REM đường khác (đã tắt Docker từ trước, dùng --local, --stop, --keep-docker,
REM hay hết 90 giây chờ) đều nhảy qua nó mà không báo gì.
if defined NO_WSL goto :report

call :step "Máy ảo WSL2 - trả RAM về cho Windows"

where wsl >nul 2>nul
if not errorlevel 1 goto :wsl_have
echo   Máy không có lệnh wsl - bỏ qua.
goto :report

:wsl_have
if defined LOCAL_ONLY goto :wsl_measure
REM Tắt riêng distro của Docker. Chạy ở MỌI đường, kể cả khi Docker Desktop
REM đã tắt sẵn hoặc quá 90 giây chưa đóng hẳn - đó là hai đường mà bản cũ bỏ
REM sót, vì lệnh này khi ấy nằm trong khối :dd_done.
echo   Đang tắt distro docker-desktop...
wsl --terminate docker-desktop >nul 2>nul

:wsl_measure
if defined KILL_WSL goto :wsl_kill

set "WSL_NOW="
for /f "delims=" %%m in ('powershell -NoProfile -Command "$ps = @(Get-Process vmmem,vmmemWSL -ErrorAction SilentlyContinue); $sum = 0; foreach ($x in $ps) { $sum += $x.WorkingSet64 }; [math]::Round($sum/1GB,2)"') do set "WSL_NOW=%%m"
if not defined WSL_NOW goto :report
if "%WSL_NOW%"=="0" goto :wsl_free

echo   Máy ảo WSL2 vẫn đang giữ %WSL_NOW% GB RAM.
echo   Tắt hẳn thì đòi lại được ngay, nhưng nó tắt MỌI distro WSL - kể cả
echo   Ubuntu hay phiên làm việc bạn đang mở ở cửa sổ khác.
set "CONFIRM="
echo   Tắt luôn máy ảo WSL2? Gõ c rồi Enter để tắt, Enter suông để bỏ qua.
set /p "CONFIRM=  > "
if /i "%CONFIRM%"=="c" goto :wsl_kill
if /i "%CONFIRM%"=="co" goto :wsl_kill
if /i "%CONFIRM%"=="y" goto :wsl_kill
echo   Giữ nguyên máy ảo. autoMemoryReclaim trong .wslconfig sẽ nhả RAM dần.
goto :report

:wsl_free
echo   Máy ảo WSL2 không giữ RAM nào - không cần tắt.
goto :report

:wsl_kill
echo   Đang tắt toàn bộ máy ảo WSL2...
wsl --shutdown
echo   Đã tắt. Lưu ý: lệnh này tắt MỌI distro WSL, không riêng của Docker.

:report
call :step "Kiểm chứng xem đã giải phóng thật chưa"
ping -n 4 127.0.0.1 >nul

REM Ba thứ chiếm tài nguyên, kiểm lại từng thứ thay vì tin là lệnh ở trên đã
REM chạy đúng: cổng còn LISTENING, container còn sống, và RAM máy ảo WSL2.
set "LEFT_PORT="
for %%p in (8080 8081 8082 8083 8084 8085 8086 8087 8090) do call :verify_port %%p
if not defined LEFT_PORT echo   [OK] Không cổng nào trong 8080-8087, 8090 còn bị chiếm.

where docker >nul 2>nul
if errorlevel 1 goto :verify_wsl
docker info >nul 2>nul
if errorlevel 1 goto :verify_engine_off
set "LEFT_CT="
for /f %%n in ('docker ps -q ^| find /c /v ""') do set "LEFT_CT=%%n"
if "%LEFT_CT%"=="0" goto :verify_ct_ok
echo   [CÒN] %LEFT_CT% container vẫn đang chạy:
docker ps --format "         {{.Names}}  ({{.Status}})"
echo         Hạ nốt bằng: end-backend.bat   hoặc   docker rm -f ^<tên^>
goto :verify_wsl
:verify_ct_ok
echo   [OK] Docker engine còn chạy nhưng không còn container nào.
goto :verify_wsl
:verify_engine_off
echo   [OK] Docker engine đã tắt - không container nào còn sống.

:verify_wsl
set "WSL_RAM="
for /f "delims=" %%m in ('powershell -NoProfile -Command "$ps = @(Get-Process vmmem,vmmemWSL -ErrorAction SilentlyContinue); $sum = 0; foreach ($x in $ps) { $sum += $x.WorkingSet64 }; [math]::Round($sum/1GB,2)"') do set "WSL_RAM=%%m"
if not defined WSL_RAM goto :verify_done
if "%WSL_RAM%"=="0" goto :verify_wsl_ok
echo   [CÒN] Máy ảo WSL2 vẫn giữ %WSL_RAM% GB RAM.
echo         Bình thường nó nhả dần nhờ autoMemoryReclaim trong .wslconfig.
echo         Muốn đòi lại ngay, không cần hỏi: end-backend.bat --wsl
goto :verify_done
:verify_wsl_ok
echo   [OK] Máy ảo WSL2 không còn giữ RAM.
:verify_done

call :step "Báo cáo RAM"
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

:step
set /a STEP+=1
set /a PCT=STEP*100/TOTAL
set /a FILL=PCT/5
set "BAR="
for /l %%i in (1,1,20) do call :bar_cell %%i
echo.
echo [%BAR%] %PCT%%% - bước %STEP%/%TOTAL%: %~1
goto :eof

:bar_cell
REM Goi qua :call de %BAR% duoc no lai moi vong - khong can delayed expansion.
if %~1 LEQ %FILL% set "BAR=%BAR%#"
if %~1 GTR %FILL% set "BAR=%BAR%."
goto :eof

:verify_port
set "VP_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":%~1 .*LISTENING"') do set "VP_PID=%%p"
if not defined VP_PID goto :eof
set "LEFT_PORT=1"
set "VP_IMG="
for /f "tokens=1 delims=," %%i in ('tasklist /FI "PID eq %VP_PID%" /FO CSV /NH 2^>nul') do set "VP_IMG=%%~i"
echo   [CÒN] Cổng %~1 vẫn bị PID %VP_PID% giữ - %VP_IMG%
goto :eof

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
REM KHÔNG dùng khối `if ... ( ... )` ở đây. Nhãn %~2 của bốn service Go là
REM "history-service (Go)" — có ngoặc đơn. cmd.exe thay %~2 vào TRƯỚC khi
REM chạy, nên dấu ")" trong "(Go)" đóng sớm khối lệnh và cả tệp chết với
REM "8085 was unexpected at this time." NGAY CẢ KHI nhánh đó không được
REM chọn, vì cmd phân tích cả khối trước rồi mới quyết định chạy hay không.
REM Đó là lỗi thật: end-backend.bat luôn dừng ở cổng 8085, bỏ lại 4 binary
REM Go, container và Docker Desktop. Dạng `goto` không có khối nên an toàn.
if errorlevel 1 goto :kill_port_do
echo   %~2 :%~1 - PID %PORT_PID% là tiến trình Docker %PORT_IMG%, bỏ qua.
goto :eof

:kill_port_do

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
echo                                  container, tắt Docker Desktop, tắt distro
echo                                  WSL2 của Docker, rồi HỎI có tắt hẳn máy ảo
echo                                  WSL2 không nếu nó vẫn còn giữ RAM
echo   end-backend.bat --local        chỉ dừng tiến trình jar, không đụng Docker
echo   end-backend.bat --keep-docker  hạ container nhưng để Docker Desktop chạy
echo   end-backend.bat --stop         chỉ dừng container, KHÔNG xoá - bật lại nhanh
echo   end-backend.bat --wipe         hạ container VÀ XOÁ volume - MẤT DỮ LIỆU
echo   end-backend.bat --wsl          tắt CẢ máy ảo WSL2 ^(mọi distro, không riêng
echo                                  Docker^) để trả RAM ngay, KHÔNG hỏi lại.
echo                                  Ghép được với mọi cờ khác, kể cả --local.
echo   end-backend.bat --no-wsl       không đụng tới máy ảo WSL2, cũng không hỏi
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
