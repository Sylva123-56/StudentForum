@echo off
setlocal
chcp 65001 >nul
set "PWSH="
for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined PWSH set "PWSH=%%P"
if not defined PWSH if exist "D:\Develop\PowerShell\7\pwsh.exe" set "PWSH=D:\Develop\PowerShell\7\pwsh.exe"
if not defined PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined PWSH (
    echo PowerShell 7 was not found. Install it or add pwsh.exe to PATH.
    pause
    exit /b 1
)
if not exist "%~dp0Start-StudentForum.ps1" (
    echo Missing Start-StudentForum.ps1. Keep both launcher files together.
    pause
    exit /b 1
)
"%PWSH%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-StudentForum.ps1" %*
if errorlevel 1 pause
endlocal
