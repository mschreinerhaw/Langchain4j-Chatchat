param([Parameter(ValueFromRemainingArguments = $true)][string[]]$StartArgs)
$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat-runtime-news"
$AppJar = Join-Path $AppHome "lib/$AppName.jar"
$RunDir = Join-Path $AppHome "run"
$LogsDir = Join-Path $AppHome "logs"
$PidFile = Join-Path $RunDir "$AppName.pid"
$AppLog = Join-Path $LogsDir "$AppName.log"
$StartupLog = Join-Path $LogsDir "$AppName-startup.log"
$StartupErrorLog = Join-Path $LogsDir "$AppName-startup-error.log"
$LogConfig = Join-Path $AppHome "config/logback-spring.xml"
$ConfigDir = (Join-Path $AppHome "config").Replace('\', '/')
New-Item -ItemType Directory -Force -Path (Join-Path $AppHome "data"), $LogsDir, (Join-Path $LogsDir "archive"), $RunDir | Out-Null
. (Join-Path $PSScriptRoot "load-env.ps1")

if (Test-Path $PidFile) {
    $OldPid = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($OldPid -and (Get-Process -Id $OldPid -ErrorAction SilentlyContinue)) { Write-Host "$AppName is already running, pid=$OldPid"; exit 0 }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}
if (-not (Test-Path $AppJar)) { Write-Error "Application jar not found: $AppJar"; exit 1 }
if (-not (Test-Path $LogConfig)) { Write-Error "Logback configuration not found: $LogConfig"; exit 1 }
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java.exe" }
$Arguments = [Collections.Generic.List[string]]::new()
if ($env:JAVA_OPTS) {
    foreach ($Value in ($env:JAVA_OPTS -split '\s+')) { if ($Value) { $Arguments.Add($Value) } }
}
$Arguments.Add("-DLOG_DIR=$($LogsDir.Replace('\', '/'))")
$Arguments.Add("-jar")
$Arguments.Add($AppJar)
$Arguments.Add("--spring.config.additional-location=optional:file:$ConfigDir/")
$Arguments.Add("--logging.config=file:$($LogConfig.Replace('\', '/'))")
if ($env:APP_ARGS) { foreach ($Value in ($env:APP_ARGS -split '\s+')) { if ($Value) { $Arguments.Add($Value) } } }
foreach ($Value in $StartArgs) { $Arguments.Add($Value) }
Set-Content -Path $StartupLog -Value "" -Encoding UTF8
Set-Content -Path $StartupErrorLog -Value "" -Encoding UTF8
$Process = Start-Process -FilePath $Java -ArgumentList $Arguments -WorkingDirectory $AppHome `
    -RedirectStandardOutput $StartupLog -RedirectStandardError $StartupErrorLog -WindowStyle Hidden -PassThru
Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII
Start-Sleep -Seconds 2
if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) {
    Write-Host "$AppName started, pid=$($Process.Id)"
    Write-Host "application log: $AppLog"
    Write-Host "startup diagnostics: $StartupLog"
    exit 0
}
Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
Write-Error "$AppName failed to start. Check $StartupLog and $StartupErrorLog"
exit 1
