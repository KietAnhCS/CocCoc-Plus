@echo off
setlocal

for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "PS1=%~dp0crawl-stats.ps1"
if not exist "%PS1%" (
    echo [LỖI] Không thấy "%PS1%".
    echo       Hai tệp crawl-stats.bat và crawl-stats.ps1 phải nằm cạnh nhau
    echo       ở thư mục gốc của kho.
    goto :fail
)

set "ARGS="
set "NOPAUSE="
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--no-pause" (
    set "NOPAUSE=1"
) else (
    set "ARGS=%ARGS% "%~1""
)
shift
goto :parse
:parsed

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %ARGS%
if errorlevel 1 goto :fail

if defined NOPAUSE goto :done
echo Nhấn phím bất kỳ để đóng...
pause >nul
:done
call :restore_cp
endlocal
exit /b 0

:fail
echo.
if not defined NOPAUSE (
    echo Nhấn phím bất kỳ để đóng...
    pause >nul
)
call :restore_cp
endlocal
exit /b 1

:restore_cp
if defined OLD_CP chcp %OLD_CP% >nul
goto :eof
