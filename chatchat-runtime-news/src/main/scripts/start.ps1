param([Parameter(ValueFromRemainingArguments = $true)][string[]]$StartArgs)
$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat-runtime-news"
$AppJar = Join-Path $AppHome "lib/$AppName.jar"
$RunDir = Join-Path $AppHome "run"
$LogsDir = Join-Path $AppHome "logs"
$PidFile = Join-Path $RunDir "$AppName.pid"
$StdoutLog = Join-Path $LogsDir "$AppName.out"
$StderrLog = Join-Path $LogsDir "$AppName.err"
$ConfigDir = (Join-Path $AppHome "config").Replace('\', '/')
New-Item -ItemType Directory -Force -Path (Join-Path $AppHome "data"), $LogsDir, $RunDir | Out-Null
. (Join-Path $PSScriptRoot "load-env.ps1")

if (Test-Path $PidFile) {
    $OldPid = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($OldPid -and (Get-Process -Id $OldPid -ErrorAction SilentlyContinue)) { Write-Host "$AppName is already running, pid=$OldPid"; exit 0 }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}
if (-not (Test-Path $AppJar)) { Write-Error "Application jar not found: $AppJar"; exit 1 }
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java.exe" }
$Arguments = [Collections.Generic.List[string]]::new()
if ($env:JAVA_OPTS) {
    foreach ($Value in ($env:JAVA_OPTS -split '\s+')) { if ($Value) { $Arguments.Add($Value) } }
}
$Arguments.Add("-jar")
$Arguments.Add($AppJar)
$Arguments.Add("--spring.config.additional-location=optional:file:$ConfigDir/")
if ($env:APP_ARGS) { foreach ($Value in ($env:APP_ARGS -split '\s+')) { if ($Value) { $Arguments.Add($Value) } } }
foreach ($Value in $StartArgs) { $Arguments.Add($Value) }
$Process = Start-Process -FilePath $Java -ArgumentList $Arguments -WorkingDirectory $AppHome `
    -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog -WindowStyle Hidden -PassThru
Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII
Start-Sleep -Seconds 2
if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) {
    Write-Host "$AppName started, pid=$($Process.Id)"
    Write-Host "log: $StdoutLog"
    exit 0
}
Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
Write-Error "$AppName failed to start. Check $StdoutLog and $StderrLog"
exit 1
