$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
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
