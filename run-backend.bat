@echo off
rem KHONG viet tieng Viet co dau trong file .bat: cmd.exe phan tich file theo
rem byte offset, ky tu da byte lam lech con tro doc va cat vun cac dong lenh
rem phia sau (ke ca khi da chcp 65001, ke ca khi luu kem BOM). Ly do day du xem
rem trong run-crawl.bat. Phan chcp ben duoi la cho OUTPUT cua Java - thu ma cmd
rem chi in ho chu khong phan tich.
setlocal

rem ===========================================================================
rem Khoi dong backend Spring Boot TREN MAY THAT (khong Docker).
rem
rem   run-backend.bat              corpus JSON + bus in-memory  <-- mac dinh
rem   run-backend.bat --postgres   lay PostgreSQL lam nguon corpus uu tien
rem   run-backend.bat --kafka      bus Kafka phan tan (can broker dang chay)
rem   run-backend.bat --bm25       doi mo hinh cham diem sang BM25
rem   run-backend.bat --help       in phan huong dan nay
rem
rem File nay danh cho vong lap phat trien: sua ma, Ctrl+C, chay lai. Muon chay
rem ca backend + PostgreSQL trong container thi dung `docker compose up -d
rem --build` (xem README.md) - o do khong can file .bat nao.
rem ===========================================================================

rem Chot duong dan goc TRUOC vong lap doc tham so. `shift` dich ca %0, nen sau
rem hai lan shift thi `%~dp0` khong con la thu muc chua file .bat nua ma la
rem thu muc suy ra tu mot THAM SO - trieu chung la "khong tim thay thu muc
rem ...\search-engine\search-engine". Loi nay chi lo ra khi co truyen tham so,
rem tuc chay tran van dung.
set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"

rem --- Doc tham so ---
set "USE_POSTGRES="
set "USE_KAFKA="
set "SCORER="

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--postgres" (
    set "USE_POSTGRES=1"
) else if /i "%~1"=="--kafka" (
    set "USE_KAFKA=1"
) else if /i "%~1"=="--bm25" (
    set "SCORER=bm25"
) else (
    echo [LOI] Tham so khong hieu: %~1
    echo.
    goto :usage_fail
)
shift
goto :parse
:parsed

rem Bang ma console: log khoi dong va thong bao loi cua ung dung deu la tieng
rem Viet co dau. O bang ma mac dinh cua Windows (437/1258) chung ra dau hoi.
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

rem --- Kiem tra thu muc va cong cu ---
cd /d "%ROOT%search-engine" 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay thu muc "%ROOT%search-engine".
    echo       File .bat nay phai nam o THU MUC GOC cua repo, canh docker-compose.yml.
    goto :fail
)

if not exist "pom.xml" (
    echo [LOI] Khong thay pom.xml trong "%CD%".
    echo       Thu muc search-engine co ve khong day du.
    goto :fail
)

rem Goi wrapper bang duong dan tuyet doi: neu bien moi truong
rem NoDefaultCurrentDirectoryInExePath duoc bat, cmd KHONG tim lenh trong thu
rem muc hien tai va "call mvnw.cmd" tran se bao khong tim thay lenh.
set "MVNW=%CD%\mvnw.cmd"
if not exist "%MVNW%" (
    echo [LOI] Khong thay Maven Wrapper ^(mvnw.cmd^) trong "%CD%".
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Java.
    echo       Can JDK 17 tro len - cai tai https://adoptium.net roi mo lai cua so nay.
    goto :fail
)
for /f "delims=" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    echo Java %%v
    goto :java_done
)
:java_done

rem --- Cong 8080 ---
rem Spring Boot se bao "Port 8080 was already in use" roi thoat sau khi da nap
rem xong ngu canh - tuc sau vai chuc giay lap chi muc. Kiem tra truoc o day de
rem hong ngay lap tuc, va de noi duoc TIEN TRINH NAO dang giu cong (thuong la
rem mot ban backend cu chua tat han, hoac container vnsearch-backend).
set "PORT_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8080 .*LISTENING"') do set "PORT_PID=%%p"
if defined PORT_PID (
    echo [LOI] Cong 8080 dang bi tien trinh PID %PORT_PID% chiem.
    echo       Xem no la gi   : tasklist /FI "PID eq %PORT_PID%"
    echo       Tat di         : taskkill /PID %PORT_PID% /F
    echo       Neu la Docker  : docker compose stop backend
    goto :fail
)

rem ===========================================================================
rem KHOA QUAN TRI
rem ===========================================================================
rem SecurityConfig TU CHOI khoi dong khi thieu app.security.admin-api-key: cac
rem endpoint /api/admin/** dieu khien crawler va tai duoc URL tuy y, nen chay
rem khong khoa la mot lo hong SSRF hoan chinh. Thu tu tim khoa o day:
rem   1. bien moi truong ADMIN_API_KEY cua phien terminal hien tai
rem   2. tep .env o goc repo (cung tep ma docker compose doc)
rem   3. sinh moi bang RNG mat ma va GHI vao .env de lan sau khong doi khoa
rem
rem Khoa doi moi lan chay se lam moi lenh curl da luu trong tai lieu thanh 401.
if defined ADMIN_API_KEY goto :key_ok
if not exist "%ENV_FILE%" goto :key_new

rem CHI lay dung nhung khoa can, KHONG nap ca .env vao moi truong.
rem
rem Ly do cu the: .env la tep cau hinh cua DOCKER COMPOSE, noi ten dich vu
rem trong mang noi bo mang y nghia khac han. Vi du no thuong chua
rem APP_CRAWLER_BUS=kafka va APP_STORAGE_POSTGRES_URL tro toi host `postgres` -
rem hai gia tri do dung o trong container, nhung o may that thi `postgres`
rem khong phan giai duoc, con bus=kafka khien KafkaCrawlConfig
rem (fatalIfBrokerNotAvailable=true) TU CHOI khoi dong khi khong co broker.
rem Nap ca tep vao day nghia la mot lan `docker compose up` co the lam
rem run-backend.bat het chay ma khong ai hieu vi sao.
rem
rem eol=# de bo qua dong chu thich; tokens=1,* delims== de gia tri con giu
rem duoc dau `=` (chuoi ket noi JDBC co dau nay).
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if /i "%%a"=="ADMIN_API_KEY" set "ADMIN_API_KEY=%%b"
    if /i "%%a"=="POSTGRES_PASSWORD" set "POSTGRES_PASSWORD=%%b"
    if /i "%%a"=="APP_CORS_ALLOWED_ORIGINS" set "APP_CORS_ALLOWED_ORIGINS=%%b"
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
rem day thi thay loi truoc khi mat vai chuc giay bien dich; de Spring bat thi
rem thay loi sau.
set "KEY_PROBE=%ADMIN_API_KEY:~15,1%"
if not defined KEY_PROBE (
    echo [LOI] ADMIN_API_KEY ngan hon 16 ky tu nen SecurityConfig se tu choi khoi dong.
    echo       Sua gia tri ADMIN_API_KEY trong "%ENV_FILE%", hoac xoa han dong do
    echo       de file nay sinh lai khoa moi.
    goto :fail
)

rem ===========================================================================
rem CHE DO CHAY
rem ===========================================================================

rem Bus su kien. Dat TUONG MINH ca hai chieu: neu chi dat khi co --kafka thi
rem gia tri APP_CRAWLER_BUS sot lai trong terminal se am tham quyet dinh ho.
if defined USE_KAFKA (
    set "APP_CRAWLER_BUS=kafka"
    rem Cong 29092 chu KHONG phai 9092. Kafka tra ve dia chi ADVERTISED cho
    rem client roi client ket noi lai bang dia chi do; docker-compose.yml khai
    rem bao hai listener - `kafka:9092` cho container va `localhost:29092` cho
    rem tien trinh tren may that. Dung 9092 o day thi bat tay dau tien thanh
    rem cong, sau do client doi sang ten `kafka` va treo.
    if not defined APP_CRAWLER_KAFKA_BOOTSTRAP set "APP_CRAWLER_KAFKA_BOOTSTRAP=localhost:29092"
) else (
    set "APP_CRAWLER_BUS=memory"
)

rem Kho tai lieu.
if defined USE_POSTGRES (
    set "APP_STORAGE_POSTGRES_ENABLED=true"
    if not defined APP_STORAGE_POSTGRES_URL set "APP_STORAGE_POSTGRES_URL=jdbc:postgresql://localhost:5432/vnsearch"
) else (
    set "APP_STORAGE_POSTGRES_ENABLED=false"
)

set "SCORER_SHOW=tfidf - mac dinh trong application.properties"
if defined SCORER set "APP_RANKING_SCORER=%SCORER%"
if defined SCORER set "SCORER_SHOW=%SCORER%"

rem Bo nho heap. Mac dinh cua JVM la 1/4 RAM may (khoang 3,8 GB tren may 16 GB),
rem con corpus data/crawled-documents.json thi lon hon nhieu so voi kich thuoc
rem tep khi da dung thanh doi tuong Java. Uoc luong trong
rem docs/Math/03-index/IndexPersistence.md: dinh diem 1,5-2,5 GB chi rieng cho
rem buoc nap chi muc. Dat rong tay o day; muon khac thi `set BACKEND_XMX=2g`
rem truoc khi chay.
if not defined BACKEND_XMX set "BACKEND_XMX=6g"

rem Bang ma cua tien trinh Maven. Con tien trinh UNG DUNG la mot JVM khac do
rem spring-boot:run tach ra, nen tham so cua no phai di qua
rem -Dspring-boot.run.jvmArguments ben duoi - MAVEN_OPTS khong voi toi.
set "MAVEN_OPTS=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 %MAVEN_OPTS%"
set "JVM_ARGS=-Xmx%BACKEND_XMX% -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

rem --- Nguon corpus se duoc dung ---
if exist "data\crawled-documents.json" (
    set "CORPUS=data\crawled-documents.json - corpus da crawl"
) else (
    set "CORPUS=data\seed-documents.json - mau demo di kem repo"
)

echo.
echo === BACKEND VNSEARCH ===
echo Thu muc      : %CD%
echo Corpus       : %CORPUS%
echo Kho tai lieu : PostgreSQL=%APP_STORAGE_POSTGRES_ENABLED%
echo Bus crawler  : %APP_CRAWLER_BUS%
echo Cham diem    : %SCORER_SHOW%
echo Heap toi da  : %BACKEND_XMX%
echo Khoa quan tri: %ADMIN_API_KEY:~0,8%... ^(day du trong .env^)

rem --- Chi muc dung san co con khop voi corpus khong ---
rem SearchEngineFacade UU TIEN data\index.json: co tep do thi no nap thang va
rem KHONG doc corpus. Nho vay khoi dong nhanh, nhung sau mot phien crawl thi
rem chi muc thanh cu hon corpus - bo tim kiem chay binh thuong, khong mot dong
rem loi nao, chi la nhung trang vua crawl khong he co trong ket qua. Trieu
rem chung de nham nhat: "crawl xong 5.000 trang ma tim gi cung khong ra".
set "INDEX_STALE="
if not exist "data\index.json" goto :stale_done
if not exist "data\crawled-documents.json" goto :stale_done
for /f "delims=" %%s in ('powershell -NoProfile -Command "if ((Get-Item data\index.json).LastWriteTime -lt (Get-Item data\crawled-documents.json).LastWriteTime) { Write-Output STALE }"') do set "INDEX_STALE=%%s"
if not defined INDEX_STALE goto :stale_done
echo.
echo [CANH BAO] data\index.json CU HON data\crawled-documents.json.
echo            Backend se nap chi muc cu, nen cac trang crawl gan day chua tim
echo            duoc. Sau khi backend len, lap lai chi muc mot lan:
echo                curl -X POST -H "X-API-Key: khoa-trong-.env" http://localhost:8080/api/admin/reindex
:stale_done

if defined USE_POSTGRES (
    set "PG_PID="
    for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":5432 .*LISTENING"') do set "PG_PID=%%p"
    if not defined PG_PID (
        echo.
        echo [CANH BAO] Khong co gi lang nghe o cong 5432 - chua thay PostgreSQL.
        echo            Backend van khoi dong duoc: PostgresDocumentStore bao khong
        echo            san sang va chuoi du phong tu lui ve corpus JSON. Muon dung
        echo            CSDL that thi: docker compose up -d postgres
    )
)

if defined USE_KAFKA (
    set "KAFKA_PID="
    for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":29092 .*LISTENING"') do set "KAFKA_PID=%%p"
    if not defined KAFKA_PID (
        echo.
        echo [LOI] Che do --kafka nhung khong co broker o localhost:29092.
        echo       Voi app.crawler.bus=kafka, ung dung TU CHOI khoi dong khi thieu
        echo       broker - co y, de khong chay am tham trong trang thai nua voi.
        echo       Bat broker: docker compose --profile kafka up -d kafka
        goto :fail
    )
)

echo.
echo Dang bien dich va khoi dong... ^(Ctrl+C de dung^)
echo Lan dau lap chi muc tren corpus lon mat vai chuc giay - chua tra loi ngay duoc.
echo.
echo Khi thay dong "Started SearchEngineApplication":
echo     http://localhost:8080/api/health
echo     http://localhost:8080/api/search?q=ha+noi
echo     chay run-frontend.bat o mot cua so khac de mo giao dien
echo.

call "%MVNW%" spring-boot:run "-Dspring-boot.run.jvmArguments=%JVM_ARGS%"
if errorlevel 1 (
    echo.
    echo [LOI] Backend ket thuc bat thuong. Cuon len xem dong loi dau tien - dong
    echo       DAU TIEN moi la nguyen nhan, phan con lai chi la chuoi goi keo theo.
    echo       Vai nguyen nhan hay gap:
    echo         - OutOfMemoryError       : set BACKEND_XMX=8g roi chay lai
    echo         - Port 8080 in use       : con mot ban backend chua tat
    echo         - Thieu admin-api-key    : xoa dong ADMIN_API_KEY trong .env, chay lai
    goto :fail
)

echo.
echo Backend da dung.
call :restore_cp
endlocal
exit /b 0

rem ===========================================================================
:usage
echo.
echo   run-backend.bat              corpus JSON + bus in-memory  ^(mac dinh^)
echo   run-backend.bat --postgres   lay PostgreSQL lam nguon corpus uu tien
echo   run-backend.bat --kafka      bus Kafka phan tan ^(can broker o localhost:29092^)
echo   run-backend.bat --bm25       doi mo hinh cham diem sang BM25
echo.
echo   Bien moi truong:
echo     ADMIN_API_KEY   khoa cho /api/admin/**. Khong dat thi lay tu .env,
echo                     khong co nua thi tu sinh va ghi vao .env.
echo     BACKEND_XMX     heap toi da cua JVM ung dung. Mac dinh 6g.
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

rem Tra bang ma ve nhu cu: chcp doi trang thai cua CA cua so console, khong
rem phai bien moi truong, nen endlocal khong don dep ho.
:restore_cp
if defined OLD_CP chcp %OLD_CP% >nul
goto :eof
