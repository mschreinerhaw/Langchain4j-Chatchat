$ErrorActionPreference = "Stop"

$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat"
$PidFile = Join-Path $AppHome "run/$AppName.pid"

if (-not (Test-Path $PidFile)) {
    Write-Host "$AppName is not running: pid file not found"
    exit 0
}

$PidValue = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
$Process = if ($PidValue) { Get-Process -Id $PidValue -ErrorAction SilentlyContinue } else { $null }

if (-not $Process) {
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "$AppName is not running: stale pid file removed"
    exit 0
}

Stop-Process -Id $Process.Id
$Process.WaitForExit(30000)

if (-not $Process.HasExited) {
    Stop-Process -Id $Process.Id -Force
}

Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
Write-Host "$AppName stopped"
