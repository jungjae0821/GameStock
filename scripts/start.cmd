@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
if errorlevel 1 (
	echo GameStock 실행에 실패했습니다. 아무 키나 누르면 종료합니다.
	pause >nul
)
