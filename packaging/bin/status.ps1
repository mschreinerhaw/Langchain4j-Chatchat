$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat"
$PidFile = Join-Path $AppHome "run/$AppName.pid"

if (-not (Test-Path $PidFile)) {
    Write-Host "$AppName is stopped"
    exit 3
}

$PidValue = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
$Process = if ($PidValue) { Get-Process -Id $PidValue -ErrorAction SilentlyContinue } else { $null }

if ($Process) {
    Write-Host "$AppName is running, pid=$PidValue"
    exit 0
}

Write-Host "$AppName is stopped: stale pid file"
exit 1
