$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendScript = Join-Path $projectRoot 'scripts\run-backend.ps1'
$frontendScript = Join-Path $projectRoot 'scripts\run.ps1'
$logDirectory = Join-Path $projectRoot 'logs'

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

function Test-ListeningPort([int] $port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

if (-not (Test-ListeningPort 8081)) {
    Start-Process powershell.exe -WorkingDirectory $projectRoot -ArgumentList @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', "`"$backendScript`""
    ) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'backend.log') -RedirectStandardError (Join-Path $logDirectory 'backend-error.log')
}

if (-not (Test-ListeningPort 8080)) {
    Start-Process powershell.exe -WorkingDirectory $projectRoot -ArgumentList @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', "`"$frontendScript`""
    ) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'frontend.log') -RedirectStandardError (Join-Path $logDirectory 'frontend-error.log')
}

Write-Host 'GameStock 실행을 시작했습니다.'
Write-Host '웹 화면: http://localhost:8080'
Write-Host '이 창을 닫아도 서버는 계속 실행됩니다.'