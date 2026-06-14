$ErrorActionPreference = "Stop"

$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat"
$AppJar = Join-Path $AppHome "lib/app/$AppName.jar"
$LogsDir = Join-Path $AppHome "logs"
$RunDir = Join-Path $AppHome "run"
$DataDir = Join-Path $AppHome "data"
$DriversDir = Join-Path $AppHome "lib/drivers"
$PidFile = Join-Path $RunDir "$AppName.pid"
$StdoutLog = Join-Path $LogsDir "$AppName.out"
$StderrLog = Join-Path $LogsDir "$AppName.err"
$ConfigDir = Join-Path $AppHome "config"

New-Item -ItemType Directory -Force -Path $LogsDir, $RunDir, $DataDir, $DriversDir | Out-Null

. (Join-Path $PSScriptRoot "load-env.ps1")
if (-not $env:SPRING_PROFILES_ACTIVE) {
    $env:SPRING_PROFILES_ACTIVE = "prod"
}

if (Test-Path $PidFile) {
    $OldPid = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($OldPid -and (Get-Process -Id $OldPid -ErrorAction SilentlyContinue)) {
        Write-Host "$AppName is already running, pid=$OldPid"
        exit 0
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $AppJar)) {
    Write-Error "Application jar not found: $AppJar"
    exit 1
}

$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$ArgumentLine = (($env:JAVA_OPTS, "-jar", "`"$AppJar`"", "--spring.config.additional-location=optional:file:$ConfigDir/", $env:APP_ARGS) |
    Where-Object { $_ }) -join " "

$Process = Start-Process `
    -FilePath $Java `
    -ArgumentList $ArgumentLine `
    -WorkingDirectory $AppHome `
    -RedirectStandardOutput $StdoutLog `
    -RedirectStandardError $StderrLog `
    -WindowStyle Hidden `
    -PassThru

Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII
Start-Sleep -Seconds 1

if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) {
    Write-Host "$AppName started, pid=$($Process.Id)"
    Write-Host "stdout log: $StdoutLog"
    exit 0
}

Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
Write-Error "$AppName failed to start. Check $StdoutLog and $StderrLog"
exit 1
