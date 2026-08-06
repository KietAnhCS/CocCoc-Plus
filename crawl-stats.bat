@echo off
rem KHONG viet tieng Viet co dau trong file .bat: cmd.exe phan tich file theo
rem byte offset, ky tu da byte lam lech con tro doc va cat vun cac dong lenh
rem phia sau. Ly do day du xem trong run-crawl.bat. Phan chu co dau nam trong
rem crawl-stats.ps1 - PowerShell doc file theo bang ma chu khong theo byte nen
rem khong dinh loi nay.
setlocal

rem Bat UTF-8 cho console de chu tieng Viet cua script PowerShell hien dung.
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul

set "PS1=%~dp0crawl-stats.ps1"
if not exist "%PS1%" (
    echo [LOI] Khong thay "%PS1%".
    echo       Hai file crawl-stats.bat va crawl-stats.ps1 phai nam canh nhau
    echo       o thu muc goc cua repo.
    goto :fail
)

rem --- Tham so ---
rem   crawl-stats.bat                      thong ke moi corpus trong search-engine/data
rem   crawl-stats.bat data/thu-nghiem.json chi mot tep
rem   crawl-stats.bat -NoLinks             bo qua phan dem lien ket (nhanh hon)
rem   them --no-pause de khong dung lai o cuoi (dung khi goi tu script khac)
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

rem -ExecutionPolicy Bypass: script nam ngay trong repo va do nguoi dung tu
rem chay, khong can vuong chinh sach mac dinh Restricted cua Windows.
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %ARGS%
if errorlevel 1 goto :fail

if defined NOPAUSE goto :done
echo Nhan phim bat ky de dong...
pause >nul
:done
call :restore_cp
endlocal
exit /b 0

:fail
echo.
if not defined NOPAUSE (
    echo Nhan phim bat ky de dong...
    pause >nul
)
call :restore_cp
endlocal
exit /b 1

rem Tra bang ma ve nhu cu: chcp doi trang thai ca cua so console, khong phai
rem bien moi truong, nen endlocal khong don dep ho.
:restore_cp
if defined OLD_CP chcp %OLD_CP% >nul
goto :eof
