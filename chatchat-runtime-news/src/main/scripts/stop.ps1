$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat-runtime-news"
$PidFile = Join-Path $AppHome "run/$AppName.pid"
if (-not (Test-Path $PidFile)) { Write-Host "$AppName is not running"; exit 0 }
$PidValue = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
$Process = if ($PidValue) { Get-Process -Id $PidValue -ErrorAction SilentlyContinue } else { $null }
if (-not $Process) { Remove-Item $PidFile -Force; Write-Host "$AppName is not running; stale pid removed"; exit 0 }
Stop-Process -Id $Process.Id
$Timeout = if ($env:CHATCHAT_STOP_TIMEOUT_SECONDS) { [int]$env:CHATCHAT_STOP_TIMEOUT_SECONDS * 1000 } else { 30000 }
$Process.WaitForExit($Timeout)
if (-not $Process.HasExited) { Stop-Process -Id $Process.Id -Force }
Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
Write-Host "$AppName stopped"
