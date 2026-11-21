@echo off
REM crawl.bat [maxPages] [maxDepth] [output] [--fresh]   (chay trong Docker, noi tiep corpus cu)
setlocal
cd /d "%~dp0"
set "A=%~1"
if "%A%"=="" (docker compose --profile crawl run --rm crawler) else docker compose --profile crawl run --rm crawler %~1 %~2 %~3 %~4
if exist "backend\data\index.json" del /q "backend\data\index.json"
echo Xong. Chay lai start.bat de lap chi muc moi.
endlocal
