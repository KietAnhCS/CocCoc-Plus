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
rem   crawl-stats.bat -NoImages            bo qua phan thong ke anh
rem   them --no-pause de khong dung lai o cuoi (dung khi goi tu script khac)
rem
rem Bao cao gom ba phan cho MOI corpus:
rem   1. Trang    : so trang, ten mien, trang khong co noi dung
rem   2. Lien ket : outlinks tong/khac nhau, hang doi con lai, uoc tinh dung luong
rem   3. Anh      : so anh, ty le co alt, so trang co anh, top ten mien, dinh dang
rem
rem Phan anh doc tu tep "<ten-corpus>.images.json" nam canh corpus - do
rem ImageStorage phia Java ghi ra o cuoi moi phien crawl. Corpus crawl bang ban
rem ma cu chua co tep nay; chay lai run-crawl.bat mot lan la co (crawl noi tiep,
rem khong mat du lieu cu).
rem
rem Sau cac corpus con mot muc rieng cho data\index.json - chi muc da dung san.
rem No KHONG duoc quet nhu corpus (ca tep 384 MB nam tren DUNG MOT dong, doc
rem kieu do ton 1,6 giay va ~770 MB RAM ma khong ra duoc so lieu nao); script
rem chi doc 4 KB dau de lay phien ban dinh dang va bo tach tu, roi SO NGAY GIO
rem voi corpus. Chi muc cu hon corpus la loi im lang dat nhat cua ca he thong:
rem bo tim kiem chay binh thuong, khong mot dong log loi nao, nhung nhung trang
rem vua crawl khong he co trong ket qua. Gap canh bao do thi goi mot lan:
rem     curl -X POST -H "X-API-Key: <khoa trong .env>" http://localhost:8080/api/admin/reindex
rem
rem data\users.json bi bo qua han - tai khoan quan tri, khong co gi de thong ke.
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
