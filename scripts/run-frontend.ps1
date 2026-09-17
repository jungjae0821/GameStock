$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

& (Join-Path $projectRoot 'node_modules\.bin\vite.cmd') --host 127.0.0.1 --port 5180
