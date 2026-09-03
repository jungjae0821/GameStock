$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin\mvn.cmd'
if (-not (Test-Path $maven)) { throw 'IntelliJ에 포함된 Maven을 찾을 수 없습니다.' }

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
Set-Location (Join-Path $projectRoot 'backend')
& $maven spring-boot:run
