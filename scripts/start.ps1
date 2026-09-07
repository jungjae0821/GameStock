$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendScript = Join-Path $projectRoot 'scripts\run-backend.ps1'
$frontendScript = Join-Path $projectRoot 'scripts\run.ps1'

function Test-ListeningPort([int] $port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

if (-not (Test-ListeningPort 8081)) {
    Start-Process powershell.exe -WorkingDirectory $projectRoot -ArgumentList @(
        '-NoExit',
        '-ExecutionPolicy', 'Bypass',
        '-File', $backendScript
    )
}

if (-not (Test-ListeningPort 8080)) {
    Start-Process powershell.exe -WorkingDirectory $projectRoot -ArgumentList @(
        '-NoExit',
        '-ExecutionPolicy', 'Bypass',
        '-File', $frontendScript
    )
}

Write-Host 'GameStock 실행을 시작했습니다.'
Write-Host '웹 화면: http://localhost:8080'