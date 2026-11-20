@echo off
setlocal

REM ==========================================================================
REM  Chạy backend search engine (một tiến trình Java, cổng 8080).
REM
REM    run-backend.bat            build jar nếu chưa có, rồi chạy
REM    run-backend.bat --build    build lại jar trước khi chạy
REM    run-backend.bat --reindex  xoá data\index.json để lập chỉ mục lại từ corpus
REM ==========================================================================

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "BUILD="
set "REINDEX="
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--build"   (set "BUILD=1"   & shift & goto :parse)
if /i "%~1"=="--reindex" (set "REINDEX=1" & shift & goto :parse)
echo [LỖI] Tham số không hiểu: %~1
echo       Dùng: run-backend.bat [--build] [--reindex]
goto :fail
:parsed

cd /d "%~dp0backend" 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy thư mục "%~dp0backend".
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [LỖI] Không tìm thấy Java. Cần JDK 17 trở lên: https://adoptium.net
    goto :fail
)

set "JAR=target\vnsearch-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" set "BUILD=1"
if defined BUILD (
    echo Đang build backend...
    call mvnw.cmd -q -B clean package -DskipTests
    if errorlevel 1 (
        echo [LỖI] Build thất bại. Cuộn lên xem lỗi của Maven.
        goto :fail
    )
)

if defined REINDEX (
    if exist "data\index.json" del /q "data\index.json"
    echo Đã xoá data\index.json - chỉ mục sẽ được lập lại từ corpus.
)

for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":8080 .*LISTENING"') do (
    echo [LỖI] Cổng 8080 đang bị tiến trình PID %%p chiếm. Chạy end-backend.bat trước.
    goto :fail
)

echo.
echo Backend chạy ở http://localhost:8080  ^(Ctrl+C để dừng^)
echo   Thử: http://localhost:8080/api/search?q=máy+tính
echo.
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%JAR%"

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
