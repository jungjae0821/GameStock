$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = $env:JAVA_HOME

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\javac.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-*-hotspot' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($jdk) {
        $javaHome = $jdk.FullName
    }
}

if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\javac.exe'))) {
    throw 'Java 21 JDK를 찾을 수 없습니다. JAVA_HOME을 Java 21 설치 폴더로 설정한 뒤 다시 실행하세요.'
}

$classes = Join-Path $projectRoot 'build\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = Get-ChildItem (Join-Path $projectRoot 'server\src') -Recurse -Filter '*.java' | ForEach-Object FullName

& (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw '서버 컴파일에 실패했습니다.' }

Set-Location $projectRoot
& (Join-Path $javaHome 'bin\java.exe') -cp $classes com.gamestock.GameStockApplication
