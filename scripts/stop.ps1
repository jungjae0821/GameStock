$ErrorActionPreference = 'Stop'

$ports = 5180, 8081
$processIds = Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique

if ($processIds) {
    $processIds | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
    Write-Host 'GameStock 서버를 종료했습니다.'
} else {
    Write-Host '실행 중인 GameStock 서버가 없습니다.'
}
