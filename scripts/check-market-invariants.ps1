[CmdletBinding()]
param(
    [string]$Database = 'gamestock'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$settings = @{}
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') {
            $settings[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
}

$username = if ($settings.ContainsKey('DB_USERNAME') -and $settings.DB_USERNAME) { $settings.DB_USERNAME } else { 'jungjae0821' }
$password = if ($settings.ContainsKey('DB_PASSWORD')) { $settings.DB_PASSWORD } else { '' }
if ([string]::IsNullOrWhiteSpace($password)) { throw '.env의 DB_PASSWORD가 필요합니다.' }

$mysql = (Get-Command mysql.exe -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrWhiteSpace($mysql)) {
    $candidate = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    if (Test-Path $candidate) { $mysql = $candidate }
}
if ([string]::IsNullOrWhiteSpace($mysql)) { throw 'mysql.exe를 찾을 수 없습니다.' }

# MYSQL_PWD is inherited only by this child process and keeps the password out
# of the command line and the captured output.
$previousMysqlPwd = $env:MYSQL_PWD
$env:MYSQL_PWD = $password
try {
    function Invoke-Scalar([string]$Query) {
        $output = & $mysql --protocol=tcp --host=localhost --port=3306 --user=$username --database=$Database --batch --skip-column-names --raw --execute $Query 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
        return [int64](($output | Select-Object -Last 1).ToString().Trim())
    }

    $checks = @(
        [pscustomobject]@{ Name = 'no_negative_cash'; Query = 'SELECT COUNT(*) FROM users WHERE cash < 0' },
        [pscustomobject]@{ Name = 'order_remaining_within_quantity'; Query = 'SELECT COUNT(*) FROM orders WHERE remaining_quantity < 0 OR remaining_quantity > quantity' },
        [pscustomobject]@{ Name = 'reservation_values_nonnegative'; Query = 'SELECT COUNT(*) FROM orders WHERE reserved_cash < 0 OR reserved_quantity < 0' },
        [pscustomobject]@{ Name = 'portfolio_quantities_valid'; Query = 'SELECT COUNT(*) FROM portfolios WHERE quantity < 0 OR settled_quantity < 0 OR settled_quantity > quantity' },
        [pscustomobject]@{ Name = 'open_orders_have_remaining'; Query = "SELECT COUNT(*) FROM orders WHERE status = 'OPEN' AND remaining_quantity = 0" },
        [pscustomobject]@{ Name = 'settled_rows_have_timestamp'; Query = "SELECT COUNT(*) FROM settlements WHERE status = 'SETTLED' AND settled_at IS NULL" },
        [pscustomobject]@{ Name = 'trades_have_settlement'; Query = 'SELECT COUNT(*) FROM trades t LEFT JOIN settlements st ON st.trade_id = t.id WHERE st.id IS NULL' },
        [pscustomobject]@{ Name = 'orders_have_stock'; Query = 'SELECT COUNT(*) FROM orders o LEFT JOIN stocks s ON s.id = o.stock_id WHERE s.id IS NULL' },
        [pscustomobject]@{ Name = 'orders_have_user'; Query = 'SELECT COUNT(*) FROM orders o LEFT JOIN users u ON u.id = o.user_id WHERE u.id IS NULL' }
    )
    $failed = 0
    foreach ($check in $checks) {
        $violations = Invoke-Scalar $check.Query
        if ($violations -ne 0) {
            $failed++
            Write-Output ('FAIL {0} violations={1}' -f $check.Name, $violations)
        }
    }
    Write-Output ('DB_INVARIANTS pass={0} fail={1} total={2}' -f ($checks.Count - $failed), $failed, $checks.Count)
    if ($failed -gt 0) { exit 1 }
} finally {
    if ($null -eq $previousMysqlPwd) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    else { $env:MYSQL_PWD = $previousMysqlPwd }
}
