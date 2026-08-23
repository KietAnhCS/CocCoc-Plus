@echo off
rem KHONG viet tieng Viet co dau trong file .bat: cmd.exe phan tich file theo
rem byte offset, ky tu da byte lam lech con tro doc va cat vun cac dong lenh
rem phia sau (ke ca khi da chcp 65001, ke ca khi luu kem BOM). Ly do day du xem
rem trong run-crawl.bat. Phan chcp ben duoi la cho OUTPUT cua Docker/Java - thu
rem ma cmd chi in ho chu khong phan tich.
setlocal

rem ===========================================================================
rem Dung TOAN BO he thong VnSearch bang Docker: build lai anh roi bat het dich vu.
rem
rem   run-backend.bat                FULL - 10 container, ~4 GB RAM  <-- mac dinh
rem   run-backend.bat --kafka        6 container: + cum Kafka        ~3 GB
rem   run-backend.bat --core         3 container: backend+postgres   ~1,5 GB
rem   run-backend.bat --no-football  bo football-service ra ngoai (bot 1 container)
rem   run-backend.bat --no-build     dung anh da co, khong build lai
rem   run-backend.bat --logs         bam theo log backend sau khi len
rem   run-backend.bat --help         in phan huong dan nay
rem
rem Dem theo docker-compose.yml hien tai - moi che do gom nhung container nao:
rem   luon co (khong profile)  postgres, backend
rem   profile kafka            kafka, kafka-ui, crawler-worker
rem   profile monitoring       kafka + 3 dich vu tren, cong prometheus, grafana,
rem                            alertmanager, kafka-exporter
rem   profile football         football-service
rem Nen: core 3, kafka 6, full 10 (tru 1 neu co --no-football).
rem
rem football-service di kem MOI che do, ke ca --core: no chi ton ~30 MB RAM va
rem dung chung Postgres san co, nhung thieu no thi phan The thao cua giao dien
rem chi con mot bang bao loi. Mot tinh nang tat ngam vi mot file .bat quen bat
rem la thu rat lau sau moi co nguoi phat hien ra.
rem
rem Tat va giai phong RAM: end-backend.bat
rem
rem File nay KHONG con chay Maven tren may that nua. Moi thu chay trong
rem container, nen ban chay giong het ban se cham diem - khong con canh "tren
rem may em no chay duoc".
rem ===========================================================================

rem Chot duong dan goc TRUOC vong lap doc tham so. `shift` dich ca %0, nen sau
rem hai lan shift thi `%~dp0` khong con la thu muc chua file .bat nua ma la thu
rem muc suy ra tu mot THAM SO. Loi nay chi lo ra khi co truyen tham so.
set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

rem --- Doc tham so ---
rem Mac dinh la FULL. Mot lan bam ra ca chuoi phan tan lan chuoi quan sat.
set "MODE=full"
set "PROFILES=--profile kafka --profile monitoring"
set "BUS=kafka"
set "BUILD=--build"
set "FOLLOW_LOGS="
set "NO_FOOTBALL="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--full" (
    set "MODE=full"
    set "PROFILES=--profile kafka --profile monitoring"
    set "BUS=kafka"
) else if /i "%~1"=="--kafka" (
    set "MODE=kafka"
    set "PROFILES=--profile kafka"
    set "BUS=kafka"
) else if /i "%~1"=="--core" (
    set "MODE=core"
    set "PROFILES="
    rem Bus PHAI ve memory o che do nay. Xem muc "BUS SU KIEN" ben duoi.
    set "BUS=memory"
) else if /i "%~1"=="--no-football" (
    set "NO_FOOTBALL=1"
) else if /i "%~1"=="--no-build" (
    set "BUILD="
) else if /i "%~1"=="--logs" (
    set "FOLLOW_LOGS=1"
) else (
    echo [LOI] Tham so khong hieu: %~1
    echo.
    goto :usage_fail
)
shift
goto :parse
:parsed

rem Gan profile `football` SAU vong lap, khong gan trong tung nhanh: `--core`
rem viet de len ca PROFILES, nen "run-backend.bat --core --no-football" va
rem "run-backend.bat --no-football --core" phai cho cung mot ket qua bat ke thu
rem tu nguoi dung go.
if not defined NO_FOOTBALL set "PROFILES=%PROFILES% --profile football"

rem Bang ma console: log cua backend va thong bao loi deu la tieng Viet co dau.
rem O bang ma mac dinh cua Windows (437/1258) chung ra dau hoi.
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

rem --- Thu muc goc ---
cd /d "%ROOT%" 2>nul
if not exist "docker-compose.yml" (
    echo [LOI] Khong thay docker-compose.yml trong "%CD%".
    echo       File .bat nay phai nam o THU MUC GOC cua repo.
    goto :fail
)

rem ===========================================================================
rem DOCKER
rem ===========================================================================
where docker >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay lenh `docker`.
    echo       Cai Docker Desktop tai https://docker.com/products/docker-desktop
    echo       roi MO LAI cua so nay ^(PATH chi duoc nap luc mo terminal^).
    goto :fail
)

rem Phan biet ro hai loi khac nhau: thieu plugin compose v2, va engine chua chay.
rem `docker compose version` chi hoi CLI nen tra loi duoc ngay ca khi engine tat.
docker compose version >nul 2>nul
if errorlevel 1 (
    echo [LOI] Docker co, nhung khong co plugin `docker compose` ^(v2^).
    echo       Ban Docker Desktop qua cu. Cap nhat len ban moi nhat.
    goto :fail
)

rem Engine da chay chua. `docker info` phai NOI CHUYEN duoc voi daemon moi
rem thanh cong, nen day la phep thu that chu khong phai kiem tra su ton tai.
docker info >nul 2>nul
if not errorlevel 1 goto :docker_ready

echo Docker Desktop chua chay - dang bat...

set "DOCKER_DESKTOP=%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%ProgramW6432%\Docker\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" set "DOCKER_DESKTOP=%LocalAppData%\Docker\Docker Desktop.exe"
if not exist "%DOCKER_DESKTOP%" (
    echo [LOI] Khong tim thay Docker Desktop.exe o cac vi tri quen thuoc.
    echo       Mo Docker Desktop bang tay, doi bieu tuong ca voi xanh, roi chay lai.
    goto :fail
)

start "" "%DOCKER_DESKTOP%"

rem Doi engine. Lan khoi dong nguoi thuong mat 30-60 giay vi Docker Desktop
rem phai dung may ao WSL2 truoc; may nguoi mat lau hon. Cho toi 240 giay roi
rem moi bo cuoc - het thoi gian o day de dang hon la de `compose up` bao mot
rem loi socket kho hieu.
set /a DOCKER_WAIT=0
:wait_docker
rem `ping -n 4` = cho 3 giay. Dung ping chu khong dung `timeout /t` vi `timeout`
rem hong ngay khi stdin bi chuyen huong (chay tu IDE, tu script khac).
ping -n 4 127.0.0.1 >nul
docker info >nul 2>nul
if not errorlevel 1 goto :docker_started
set /a DOCKER_WAIT+=3
if %DOCKER_WAIT% GEQ 240 (
    echo.
    echo [LOI] Doi 4 phut ma Docker engine van chua san sang.
    echo       Mo Docker Desktop xem no bao gi ^(hay gap: WSL2 chua cai, hoac
    echo       Virtualization tat trong BIOS^).
    goto :fail
)
echo    ... %DOCKER_WAIT%s
goto :wait_docker

:docker_started
echo Docker Desktop da san sang sau %DOCKER_WAIT%s.

:docker_ready

rem ===========================================================================
rem KHOA QUAN TRI
rem ===========================================================================
rem docker-compose.yml khai bao ADMIN_API_KEY voi cu phap `${...:?}`, tuc
rem `docker compose` DUNG NGAY neu bien nay trong. Co y: cac endpoint
rem /api/admin/** dieu khien crawler va tai duoc URL tuy y, nen chay khong khoa
rem la mot lo hong SSRF hoan chinh.
rem
rem Thu tu tim khoa:
rem   1. bien moi truong ADMIN_API_KEY cua phien terminal hien tai
rem   2. tep .env o goc repo (cung tep ma docker compose doc)
rem   3. sinh moi bang RNG mat ma va GHI vao .env de lan sau khong doi khoa
rem
rem Khoa doi moi lan chay se lam moi lenh curl da luu trong tai lieu thanh 401.
if defined ADMIN_API_KEY goto :key_ok
if not exist "%ENV_FILE%" goto :key_new

rem CHI lay dung khoa can, KHONG nap ca .env vao moi truong: nhung gia tri con
rem lai trong .env viet cho MANG NOI BO cua compose (host `postgres`, host
rem `kafka`) - keo ra terminal la vo nghia.
rem
rem eol=# de bo qua dong chu thich; tokens=1,* delims== de gia tri con giu duoc
rem dau `=` neu co.
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
)
if defined ADMIN_API_KEY (
    echo Khoa quan tri : doc tu "%ENV_FILE%"
    goto :key_ok
)

:key_new
echo Khoa quan tri : chua co, dang sinh khoa moi...
rem RandomNumberGenerator chu khong phai Get-Random: Get-Random dung mot bo sinh
rem so gia ngau nhien thong thuong, doan duoc neu biet hat giong. Day la khoa
rem xac thuc chu khong phai so may man.
for /f "delims=" %%k in ('powershell -NoProfile -Command "$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ([BitConverter]::ToString($b) -replace [char]45, [string]::Empty).ToLower()"') do set "ADMIN_API_KEY=%%k"
if not defined ADMIN_API_KEY (
    echo [LOI] Khong sinh duoc khoa ^(khong goi duoc PowerShell^).
    echo       Dat tay roi chay lai:
    echo           set ADMIN_API_KEY=mot-chuoi-bat-ky-tu-16-ky-tu-tro-len
    goto :fail
)
if not exist "%ENV_FILE%" (
    >"%ENV_FILE%" echo # Sinh tu dong boi run-backend.bat. KHONG commit - .gitignore da chan.
)
>>"%ENV_FILE%" echo ADMIN_API_KEY=%ADMIN_API_KEY%
echo                 da ghi vao "%ENV_FILE%" ^(tep nay khong len Git^)

:key_ok
rem Do dai toi thieu 16 ky tu - dung nguong ma SecurityConfig kiem tra. Bat o
rem day thi thay loi truoc khi mat vai phut build anh.
set "KEY_PROBE=%ADMIN_API_KEY:~15,1%"
if not defined KEY_PROBE (
    echo [LOI] ADMIN_API_KEY ngan hon 16 ky tu nen SecurityConfig se tu choi khoi dong.
    echo       Sua gia tri ADMIN_API_KEY trong "%ENV_FILE%", hoac xoa han dong do
    echo       de file nay sinh lai khoa moi.
    goto :fail
)

rem ===========================================================================
rem BUS SU KIEN
rem ===========================================================================
rem Dat TUONG MINH trong phien nay. Bien moi truong cua terminal DE LEN gia tri
rem trong .env khi compose noi suy `${APP_CRAWLER_BUS}`, va day chinh la cho can
rem no de len:
rem
rem   - Che do full/kafka: phai la `kafka`. Neu backend van chay bus `memory`
rem     thi ha tang phan tan dung day du - kafka healthy, topic ton tai,
rem     crawler-worker healthy - ma KHONG CO GI chay qua, vi backend giu
rem     endpoint POST /api/admin/crawl lai crawl in-process. Loi nay im lang
rem     tuyet doi: khong mot dong log loi nao, chi la topic rong mai mai.
rem
rem   - Che do --core: phai la `memory`. Tep .env cua may nay dang de
rem     APP_CRAWLER_BUS=kafka; de nguyen thi backend khoi dong voi
rem     fatalIfBrokerNotAvailable=true trong khi khong bat Kafka, va no TU CHOI
rem     khoi dong. Co y - hong to hon hong am tham - nhung khong duoc de nguoi
rem     bam file nay dam vao.
set "APP_CRAWLER_BUS=%BUS%"

rem ===========================================================================
rem CHAY
rem ===========================================================================
rem KHONG dat dau ngoac don vao cac chuoi nay. Trong `set "X=..."` dau ngoac
rem duoc luu nguyen van, va `^(` cung vay - echo se in ra ca dau mu. Muon co
rem ngoac thi phai echo thang, khong qua bien.
if "%MODE%"=="full"  set "MODE_SHOW=FULL - backend + postgres + kafka + monitoring, ~4 GB RAM"
if "%MODE%"=="kafka" set "MODE_SHOW=KAFKA - backend + postgres + cum Kafka, ~3 GB RAM"
if "%MODE%"=="core"  set "MODE_SHOW=CORE - backend + postgres, ~1,5 GB RAM"

rem Co khoa API-Football chua. Chi de BAO CHO BIET, khong phai dieu kien chay:
rem thieu khoa thi service van len, chi la du lieu bong da la du lieu mau. Nhung
rem "vi sao ti so khong doi" la cau hoi rat de mat thoi gian neu khong ai noi
rem thang ra, nen no duoc in ngay o dong trang thai.
set "FB_KEY="
if defined FOOTBALL_API_KEY set "FB_KEY=%FOOTBALL_API_KEY%"
if not defined FB_KEY if exist "%ENV_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
        if /i "%%a"=="FOOTBALL_API_KEY" set "FB_KEY=%%b"
    )
)

set "FOOTBALL_SHOW=co - nhung CHUA co khoa API, dang chay du lieu mau"
if defined FB_KEY set "FOOTBALL_SHOW=co - co khoa API, du lieu that"
if defined NO_FOOTBALL set "FOOTBALL_SHOW=khong - do co --no-football"

set "BUILD_SHOW=co - build lai anh tu ma nguon"
if not defined BUILD set "BUILD_SHOW=khong - dung anh da co, do co --no-build"

echo.
echo === VNSEARCH ===
echo Thu muc      : %CD%
echo Che do       : %MODE_SHOW%
echo Build        : %BUILD_SHOW%
echo Bong da      : %FOOTBALL_SHOW%
echo Bus crawler  : %APP_CRAWLER_BUS%
echo Khoa quan tri: %ADMIN_API_KEY:~0,8%... ^(day du trong .env^)
rem --- Chi muc dung san co con khop voi corpus khong ---
rem SearchEngineFacade UU TIEN data\index.json: co tep do thi no nap thang va
rem KHONG doc corpus. Nho vay khoi dong nhanh, nhung sau mot phien crawl thi chi
rem muc thanh cu hon corpus - bo tim kiem chay binh thuong, khong mot dong loi
rem nao, chi la nhung trang vua crawl khong he co trong ket qua. Trieu chung de
rem nham nhat: "crawl xong 5.000 trang ma tim gi cung khong ra".
rem
rem Van kiem tra duoc du chay trong container: docker-compose.yml mount
rem ./search-engine/data vao /app/data, tuc hai tep nay nam tren may that.
set "INDEX_STALE="
if not exist "search-engine\data\index.json" goto :stale_done
if not exist "search-engine\data\crawled-documents.json" goto :stale_done
for /f "delims=" %%s in ('powershell -NoProfile -Command "if ((Get-Item search-engine\data\index.json).LastWriteTime -lt (Get-Item search-engine\data\crawled-documents.json).LastWriteTime) { Write-Output STALE }"') do set "INDEX_STALE=%%s"
if not defined INDEX_STALE goto :stale_done
echo [CANH BAO] data\index.json CU HON data\crawled-documents.json.
echo            Backend se nap chi muc cu, nen cac trang crawl gan day chua tim
echo            duoc. Sau khi backend len, lap lai chi muc mot lan:
echo                curl -X POST -H "X-API-Key: %ADMIN_API_KEY:~0,8%..." http://localhost:8080/api/admin/reindex
:stale_done

rem --- Container mac ket o mot mang Docker da bi xoa ---
rem Container ghi CUNG dinh danh mang vao cau hinh cua no luc duoc tao, chu
rem khong tra cuu lai theo ten. Khi Docker Desktop khoi dong lai giua hai phien
rem lam viec, mang `search-engine_default` bi xoa roi tao lai voi mot dinh danh
rem MOI - va nhung container tu phien truoc van tro vao dinh danh cu da chet.
rem Compose khong tu chua: no tao mang moi, roi `up` gay giua chung voi
rem     failed to set up container networking: network <id> not found
rem Trieu chung de nham: mot vai container len binh thuong (nhung container
rem compose vua TAO LAI se nam tren mang moi), so con lai chet - nen nhin nhu
rem loi cua rieng kafka hay prometheus.
rem
rem Cach chua duy nhat la XOA HAN container do de compose tao lai. `docker rm`
rem khong dung toi volume, nen CSDL va lich su so lieu do van con nguyen.
set "STALE_LIST="
for /f "delims=" %%c in ('docker compose %PROFILES% ps -a --format "{{.Name}}" 2^>nul') do call :check_net %%c
if defined STALE_LIST (
    echo.
    echo Don container mac ket o mang cu:%STALE_LIST%
    docker rm -f %STALE_LIST% >nul 2>nul
)

echo.
echo Dang build va khoi dong... ^(lan dau tai anh nen mat vai phut^)
echo.

docker compose %PROFILES% up -d %BUILD%
if errorlevel 1 (
    echo.
    echo [LOI] `docker compose up` that bai. Cuon len xem dong loi DAU TIEN -
    echo       phan con lai thuong chi la he qua. Vai nguyen nhan hay gap:
    echo         - port is already allocated : con mot ban cu dang chay.
    echo                                       Chay end-backend.bat roi thu lai.
    echo         - no space left on device   : docker system prune -a
    echo         - build that bai o buoc mvn : loi bien dich that trong ma nguon
    goto :fail
)

rem --- Doi backend that su san sang ---
rem `up -d` tra ve ngay khi container DA TAO, khong phai khi ung dung da phuc
rem vu duoc. Backend con phai nap corpus va lap chi muc - vai chuc giay tren
rem corpus lon. Mo trinh duyet trong khoang do thi nhan Connection refused va
rem tuong la hong.
echo.
echo Container da tao. Dang doi backend nap chi muc...
set /a HEALTH_WAIT=0
:wait_backend
set "HEALTH="
for /f "delims=" %%s in ('docker inspect -f "{{.State.Health.Status}}" vnsearch-backend 2^>nul') do set "HEALTH=%%s"
if "%HEALTH%"=="healthy" goto :backend_up
if "%HEALTH%"=="" (
    echo [CANH BAO] Khong doc duoc trang thai container vnsearch-backend.
    goto :backend_unknown
)
set /a HEALTH_WAIT+=5
if %HEALTH_WAIT% GEQ 420 (
    echo.
    echo [CANH BAO] Doi 7 phut ma backend van "%HEALTH%".
    echo            Xem no ket o dau : docker compose logs -f backend
    goto :backend_unknown
)
ping -n 6 127.0.0.1 >nul
echo    ... %HEALTH_WAIT%s ^(%HEALTH%^)
goto :wait_backend

:backend_up
echo Backend san sang sau %HEALTH_WAIT%s.
:backend_unknown

echo.
docker compose %PROFILES% ps
echo.
echo === DIA CHI ===
echo   Backend API   http://localhost:8080/api/health
echo   Thu tim kiem  http://localhost:8080/api/search?q=ha+noi
if not defined NO_FOOTBALL (
    echo   Bong da       http://localhost:8090/api/v1/status
)
if not defined NO_FOOTBALL if not defined FB_KEY (
    echo.
    echo   Phan bong da dang chay DU LIEU MAU. Muon ti so that:
    echo     1. Lay khoa mien phi tai https://www.api-football.com/
    echo     2. Dan vao dong FOOTBALL_API_KEY= trong tep .env o thu muc goc
    echo     3. docker compose --profile football up -d football-service
)
if not "%MODE%"=="core" (
    echo   Kafka UI      http://localhost:8081
)
if "%MODE%"=="full" (
    echo   Grafana       http://localhost:3000    admin / xem GRAFANA_PASSWORD trong .env
    echo   Prometheus    http://localhost:9090
    echo   Alertmanager  http://localhost:9093
)
echo.
echo   Giao dien     chay run-frontend.bat o mot cua so khac
echo   Xem log       docker compose logs -f backend
echo   Do corpus     crawl-stats.bat        ^(so trang, anh, chi muc con khop khong^)
echo   TAT HET       end-backend.bat        ^<-- nho chay de tra lai RAM
echo.

if defined FOLLOW_LOGS (
    echo Dang bam theo log backend. Ctrl+C de thoat - container VAN CHAY tiep.
    echo.
    docker compose logs -f backend
)

call :restore_cp
endlocal
exit /b 0

rem ===========================================================================
rem Container %1 co dinh vao mot mang khong con ton tai khong. Co thi ghi ten no
rem vao STALE_LIST.
rem
rem Phai la mot chuong trinh con chu khong the viet gon trong vong lap goi no:
rem trong mot khoi ngoac don, cmd noi suy %STALE_LIST% mot lan duy nhat luc phan
rem tich CA KHOI, nen moi lan gan deu ghi de len chinh gia tri ban dau va danh
rem sach chi con dung mot ten. `call` bat cmd phan tich lai tung dong ngay truoc
rem khi chay, nen phep noi chuoi tich luy dung. (Cach khac la
rem `setlocal enabledelayedexpansion` voi cu phap !VAR!, nhung enabledelayed
rem expansion an ca dau `!` trong moi chuoi khac cua file - gia dat hon.)
:check_net
set "NETS="
for /f "delims=" %%n in ('docker inspect -f "{{range .NetworkSettings.Networks}}{{.NetworkID}} {{end}}" %1 2^>nul') do set "NETS=%%n"
if not defined NETS goto :eof
for %%i in (%NETS%) do (
    rem `network inspect` HOI daemon nen day la phep thu that. Dinh danh cua mot
    rem mang da xoa khong con tra loi duoc, va do la toan bo phep thu.
    docker network inspect %%i >nul 2>nul
    if errorlevel 1 goto :net_stale
)
goto :eof
rem `goto` nhay ra khoi vong lap va cat luon phan con lai cua no - vua thoat som
rem vua khoi phai chong trung khi container dinh nhieu mang chet cung luc.
:net_stale
set "STALE_LIST=%STALE_LIST% %1"
goto :eof

rem ===========================================================================
:usage
echo.
echo   run-backend.bat                FULL - 10 container ^(+ kafka + monitoring^)
echo   run-backend.bat --kafka        6 container  ^(+ cum Kafka^)
echo   run-backend.bat --core         3 container  ^(backend + postgres + football^)
echo   run-backend.bat --no-football  bo football-service ra ngoai, bot 1 container
echo   run-backend.bat --no-build     dung anh da co, khong build lai
echo   run-backend.bat --logs         bam theo log backend sau khi len
echo.
echo   football-service di kem MOI che do ^(cong 8090, ~30 MB RAM^). Thieu no thi
echo   phan The thao cua giao dien chi con mot bang bao loi.
echo.
echo   Tat va giai phong RAM: end-backend.bat
echo.
echo   Bien moi truong:
echo     ADMIN_API_KEY     khoa cho /api/admin/**. Khong dat thi lay tu .env,
echo                       khong co nua thi tu sinh va ghi vao .env.
echo     FOOTBALL_API_KEY  khoa API-Football. Khong dat thi football-service
echo                       chay bang du lieu mau - van len, van co giao dien.
echo.
call :restore_cp
endlocal
exit /b 0

:usage_fail
echo   Chay "run-backend.bat --help" de xem cac tham so hop le.
echo.
echo Nhan phim bat ky de dong...
pause >nul
endlocal
exit /b 1

:fail
echo.
echo Nhan phim bat ky de dong...
pause >nul
call :restore_cp
endlocal
exit /b 1

rem Tra bang ma ve nhu cu: chcp doi trang thai cua CA cua so console, khong phai
rem bien moi truong, nen endlocal khong don dep ho.
:restore_cp
if defined OLD_CP chcp %OLD_CP% >nul
goto :eof
