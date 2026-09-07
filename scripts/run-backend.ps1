$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

$mysqlService = Get-Service -Name MySQL80 -ErrorAction SilentlyContinue
if (-not $mysqlService) {
	throw 'MySQL80 서비스가 등록되어 있지 않습니다. README.md의 MySQL 서비스 등록 명령을 관리자 권한 PowerShell에서 한 번 실행하세요.'
}
if ($mysqlService.Status -ne 'Running') {
	Start-Service -Name MySQL80
	$mysqlService.WaitForStatus('Running', [TimeSpan]::FromSeconds(15))
}

$maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.6.2\plugins\maven\lib\maven3\bin\mvn.cmd'
if (-not (Test-Path $maven)) { throw 'IntelliJ에 포함된 Maven을 찾을 수 없습니다.' }

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$envFile = Join-Path $projectRoot '.env'
if (Test-Path $envFile) {
	Get-Content $envFile | ForEach-Object {
		if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') {
			[Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
		}
	}
}
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'jungjae0821' }
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
	throw '.env 파일에 DB_PASSWORD를 설정한 뒤 다시 실행하세요.'
}

Set-Location (Join-Path $projectRoot 'backend')
& $maven spring-boot:run
