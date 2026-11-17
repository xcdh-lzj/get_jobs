@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-windows.ps1" %*
set "GETJOBS_EXIT_CODE=%ERRORLEVEL%"
if not "%GETJOBS_EXIT_CODE%"=="0" pause
exit /b %GETJOBS_EXIT_CODE%
