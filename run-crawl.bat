@echo off
setlocal

set "RUNNER=com.vnsearch.crawler.MultiDomainCrawlRunner"

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "MAVEN_OPTS=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 %MAVEN_OPTS%"

if not defined CRAWL_PROGRESS set "CRAWL_PROGRESS=bar"

set "MAX_PAGES=%~1"
set "MAX_DEPTH=%~2"
set "OUTPUT=%~3"
set "FRESH=%~4"

if "%MAX_PAGES%"=="" set "MAX_PAGES=10000"
if "%MAX_DEPTH%"=="" set "MAX_DEPTH=4"
if "%OUTPUT%"==""    set "OUTPUT=%~dp0backend\data\crawled-documents.json"

cd /d "%~dp0backend\java" 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy thư mục "%~dp0backend\java".
    echo       Tệp .bat này phải nằm ở THƯ MỤC GỐC của kho, cạnh docker-compose.yml.
    goto :fail
)

if not exist "pom.xml" (
    echo [LỖI] Không thấy pom.xml trong "%CD%".
    echo       Thư mục backend\java có vẻ không đầy đủ.
    goto :fail
)

if not exist "libs\core-crawler\pom.xml" (
    echo [LỖI] Không thấy module "libs\core-crawler" - nơi chứa các runner crawl.
    echo       Module này tách ra từ "libs\core" cũ; nếu kho của bạn vẫn còn
    echo       "libs\core" thì hãy kéo bản mới nhất về.
    goto :fail
)

set "MVNW=%CD%\mvnw.cmd"
if not exist "%MVNW%" (
    echo [LỖI] Không thấy Maven Wrapper ^(mvnw.cmd^) trong "%CD%".
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy Java.
    echo       Cần JDK 17 trở lên - cài tại https://adoptium.net rồi mở lại cửa sổ này.
    goto :fail
)
for /f "delims=" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    echo Java %%v
    goto :java_done
)
:java_done

echo.
echo === CRAWL ĐA DOMAIN ===
echo Số trang tối đa : %MAX_PAGES%
echo Độ sâu tối đa   : %MAX_DEPTH%
echo Ngôn ngữ        : CHỈ tiếng Việt và tiếng Anh
echo Tệp đầu ra      : %OUTPUT%

if /i "%FRESH%"=="--fresh" goto :ask_fresh

if exist "%OUTPUT%" (
    echo Chế độ          : NỐI TIẾP corpus sẵn có ^(không tải lại trang đã có^)
) else (
    echo Chế độ          : crawl mới ^(chưa có corpus nào tại đường dẫn này^)
)
set "EXEC_ARGS=%MAX_PAGES% %MAX_DEPTH% %OUTPUT%"
goto :run

:ask_fresh
if not exist "%OUTPUT%" (
    echo Chế độ          : --fresh ^(chưa có corpus cũ nên không mất gì^)
    set "EXEC_ARGS=%MAX_PAGES% %MAX_DEPTH% %OUTPUT% --fresh"
    goto :run
)
echo Chế độ          : --fresh - XOÁ corpus cũ và crawl lại từ đầu
echo.
echo [CẢNH BÁO] "%OUTPUT%" đang tồn tại và sẽ bị GHI ĐÈ.
echo            Toàn bộ công crawl của các phiên trước sẽ mất.
echo.
set "CONFIRM="
set /p "CONFIRM=Gõ XOA rồi Enter để xác nhận, hoặc Enter để huỷ: "
if /i not "%CONFIRM%"=="XOA" (
    echo.
    echo Đã huỷ. Không có gì bị thay đổi.
    goto :fail
)
set "EXEC_ARGS=%MAX_PAGES% %MAX_DEPTH% %OUTPUT% --fresh"

:run
echo.
echo Đang biên dịch và chạy crawler...
echo   Ctrl+C để dừng. Điểm kiểm tra ghi mỗi max^(250 trang, 25%% corpus hiện có^),
echo   nên ở corpus lớn có thể mất vài nghìn trang cuối.
echo.

REM Build va chay TACH LAM HAI: "exec:java" gop voi "-am" se chay tren ca
REM module gop "vnsearch-parent" (khong co lop) va chet vi ClassNotFoundException.
call "%MVNW%" -q -pl libs/core-crawler -am install -DskipTests
if errorlevel 1 (
    echo.
    echo [LỖI] Biên dịch thất bại. Cuộn lên xem thông báo lỗi của Maven ở trên.
    goto :fail
)
call "%MVNW%" -q -pl libs/core-crawler exec:java -Dexec.mainClass=%RUNNER% -Dexec.args="%EXEC_ARGS%" -Dcrawl.progress=%CRAWL_PROGRESS%
if errorlevel 1 (
    echo.
    echo [LỖI] Phiên crawl kết thúc bất thường.
    echo       Cuộn lên xem thông báo lỗi của Maven/crawler ở trên.
    echo       Phần đã crawl tới điểm kiểm tra gần nhất vẫn nằm trong "%OUTPUT%".
    goto :fail
)

echo.
echo Xong. Corpus đã lưu tại "%OUTPUT%".
echo Muốn kết quả vào bộ tìm kiếm thì khởi động lại backend, hoặc gọi:
echo     curl -X POST -H "X-API-Key: khoa-trong-.env" http://localhost:8083/api/admin/reindex
echo   ^(gọi thẳng crawler-service :8083. Qua Gateway :8080 thì tuyến
echo   /api/admin/** đòi token JWT có vai trò ADMIN, không nhận X-API-Key.^)
echo.
echo Nhấn phím bất kỳ để đóng...
pause >nul
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
