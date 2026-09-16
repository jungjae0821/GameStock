[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081/api',
    [string]$FirebaseIdToken = '',
    [switch]$RunOrderScenarios
)

$ErrorActionPreference = 'Stop'
$results = [System.Collections.Generic.List[object]]::new()

function Invoke-CheckedRequest {
    param(
        [string]$Name,
        [ValidateSet('GET', 'POST', 'PATCH', 'DELETE')]
        [string]$Method,
        [string]$Path,
        [int]$Expected,
        [string]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $status = -1
    $content = ''
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $request = @{
            UseBasicParsing = $true
            Method = $Method
            Uri = ($BaseUrl.TrimEnd('/') + $Path)
            TimeoutSec = 10
            Headers = $Headers
        }
        if (-not [string]::IsNullOrEmpty($Body)) {
            $request.Body = $Body
            $request.ContentType = 'application/json'
        }
        $response = Invoke-WebRequest @request
        $status = [int]$response.StatusCode
        $content = $response.Content
    } catch {
        $errorMessage = $_.Exception.Message
        if ($null -ne $_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { $status = -1 }
        }
    } finally {
        $watch.Stop()
    }
    $passed = $status -eq $Expected
    $null = $results.Add([pscustomobject]@{
        Name = $Name
        Expected = $Expected
        Actual = $status
        LatencyMs = $watch.ElapsedMilliseconds
        Pass = $passed
        Content = $content
    })
    if (-not $passed) { Write-Host ('FAIL {0} expected={1} actual={2} error={3}' -f $Name, $Expected, $status, $errorMessage) }
    return $content
}

function Get-Json($Path, [hashtable]$Headers = @{}) {
    $raw = Invoke-CheckedRequest -Name ('GET ' + $Path) -Method GET -Path $Path -Expected 200 -Headers $Headers
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return $raw | ConvertFrom-Json
}

$publicPaths = @(
    '/health', '/stocks', '/market-events', '/market-status', '/ranking',
    '/stocks/UMA/news', '/stocks/UMA/trades', '/stocks/UMA/orderbook',
    '/stocks/UMA/history', '/stocks/UMA/price-drivers', '/stocks/UMA/daily',
    '/han-river-temperature'
)
foreach ($path in $publicPaths) {
    Invoke-CheckedRequest -Name ('public ' + $path) -Method GET -Path $path -Expected 200 | Out-Null
}

# Valid order JSON is intentional: @Valid must not return 400 before the
# authentication guard is reached.
$protectedCases = @(
    @{ Name = 'profile requires login'; Method = 'GET'; Path = '/profile'; Body = $null },
    @{ Name = 'portfolio requires login'; Method = 'GET'; Path = '/portfolio'; Body = $null },
    @{ Name = 'orders requires login'; Method = 'GET'; Path = '/orders'; Body = $null },
    @{ Name = 'settlements requires login'; Method = 'GET'; Path = '/settlements'; Body = $null },
    @{ Name = 'order history requires login'; Method = 'GET'; Path = '/orders/UMA'; Body = $null },
    @{ Name = 'google auth rejects missing token'; Method = 'POST'; Path = '/auth/google'; Body = '{}' },
    @{ Name = 'order rejects missing token'; Method = 'POST'; Path = '/orders'; Body = '{"stockCode":"UMA","side":"BUY","quantity":1,"orderType":"MARKET"}' },
    @{ Name = 'cancel rejects missing token'; Method = 'DELETE'; Path = '/orders/1'; Body = $null },
    @{ Name = 'account reset rejects missing token'; Method = 'DELETE'; Path = '/account/reset'; Body = $null },
    @{ Name = 'admin rejects missing token'; Method = 'GET'; Path = '/admin/health'; Body = $null }
)
foreach ($case in $protectedCases) {
    Invoke-CheckedRequest -Name $case.Name -Method $case.Method -Path $case.Path -Expected 401 -Body $case.Body | Out-Null
}

$stocks = Get-Json '/stocks'
foreach ($stock in @($stocks)) {
    $news = @(Get-Json ('/stocks/{0}/news' -f $stock.code))
    $drivers = Get-Json ('/stocks/{0}/price-drivers' -f $stock.code)
    $orderbook = Get-Json ('/stocks/{0}/orderbook' -f $stock.code)
    $null = $results.Add([pscustomobject]@{ Name = "news max 5 $($stock.code)"; Expected = $true; Actual = ($news.Count -le 5); LatencyMs = 0; Pass = ($news.Count -le 5); Content = '' })
    $null = $results.Add([pscustomobject]@{ Name = "driver price matches $($stock.code)"; Expected = $stock.price; Actual = $drivers.currentPrice; LatencyMs = 0; Pass = ($stock.price -eq $drivers.currentPrice); Content = '' })
    $null = $results.Add([pscustomobject]@{ Name = "orderbook shape $($stock.code)"; Expected = $true; Actual = (($null -ne $orderbook.bids) -and ($null -ne $orderbook.asks)); LatencyMs = 0; Pass = (($null -ne $orderbook.bids) -and ($null -ne $orderbook.asks)); Content = '' })
}

if (-not [string]::IsNullOrWhiteSpace($FirebaseIdToken)) {
    $authHeaders = @{ Authorization = "Bearer $FirebaseIdToken" }
    foreach ($path in @('/profile', '/portfolio', '/orders', '/settlements')) {
        Invoke-CheckedRequest -Name ('authenticated ' + $path) -Method GET -Path $path -Expected 200 -Headers $authHeaders | Out-Null
    }
    if ($RunOrderScenarios) {
        $stocks = Get-Json '/stocks'
        $uma = @($stocks | Where-Object { $_.code -eq 'UMA' })[0]
        $tick = if ($uma.price -le 1000) { 1 } elseif ($uma.price -le 5000) { 5 } elseif ($uma.price -le 50000) { 10 } elseif ($uma.price -le 100000) { 50 } else { 100 }
        $limitPrice = [math]::Max($tick, ([math]::Floor(($uma.price - 3 * $tick) / $tick) * $tick))
        $before = Get-Json '/portfolio' $authHeaders
        $activeBefore = @(Get-Json '/orders' $authHeaders)
        $knownOrderIds = @($activeBefore | ForEach-Object { [long]$_.id })
        $orderBody = @{ stockCode = 'UMA'; side = 'BUY'; quantity = 1; orderType = 'LIMIT'; price = [long]$limitPrice } | ConvertTo-Json -Compress
        $orderRaw = Invoke-CheckedRequest -Name 'authenticated limit order' -Method POST -Path '/orders' -Expected 201 -Body $orderBody -Headers $authHeaders
        $created = if ($orderRaw) { $orderRaw | ConvertFrom-Json } else { $null }
        $active = @(Get-Json '/orders' $authHeaders)
        $candidate = @($active | Where-Object {
            $_.stockCode -eq 'UMA' -and $_.side -eq 'BUY' -and
            ($knownOrderIds -notcontains [long]$_.id)
        }) | Select-Object -First 1
        if ($null -ne $candidate) {
            Invoke-CheckedRequest -Name 'cancel authenticated limit order' -Method DELETE -Path ('/orders/{0}' -f $candidate.id) -Expected 204 -Headers $authHeaders | Out-Null
            $after = Get-Json '/portfolio' $authHeaders
            $null = $results.Add([pscustomobject]@{ Name = 'limit reservation released'; Expected = $before.cash; Actual = $after.cash; LatencyMs = 0; Pass = ($before.cash -eq $after.cash); Content = '' })
        } else {
            $null = $results.Add([pscustomobject]@{ Name = 'limit reservation released'; Expected = 'order id'; Actual = 'not found'; LatencyMs = 0; Pass = $false; Content = '' })
        }
    }
}

$failed = @($results | Where-Object { -not $_.Pass })
$requestCount = @($results | Where-Object { $_.LatencyMs -gt 0 }).Count
$average = if ($requestCount -gt 0) { [math]::Round((($results | Where-Object { $_.LatencyMs -gt 0 } | Measure-Object LatencyMs -Average).Average), 1) } else { 0 }
Write-Output ('MARKET_QUALITY pass={0} fail={1} checks={2} requests={3} avgLatencyMs={4}' -f ($results.Count - $failed.Count), $failed.Count, $results.Count, $requestCount, $average)
if ($failed.Count -gt 0) { exit 1 }
