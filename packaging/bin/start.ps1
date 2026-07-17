param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$StartArgs
)

$ErrorActionPreference = "Stop"

$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat"
$AppJar = Join-Path $AppHome "lib/app/$AppName.jar"
$LogsDir = Join-Path $AppHome "logs"
$RunDir = Join-Path $AppHome "run"
$DataDir = Join-Path $AppHome "data"
$LibDir = Join-Path $AppHome "lib"
$ExtLibDir = Join-Path $LibDir "ext"
$DriversDir = Join-Path $AppHome "lib/drivers"
$PidFile = Join-Path $RunDir "$AppName.pid"
$StdoutLog = Join-Path $LogsDir "$AppName.out"
$StderrLog = Join-Path $LogsDir "$AppName.err"
$ConfigDir = Join-Path $AppHome "config"

New-Item -ItemType Directory -Force -Path $LogsDir, $RunDir, $DataDir, $ExtLibDir, $DriversDir | Out-Null

. (Join-Path $PSScriptRoot "load-env.ps1")

$ForwardArgs = New-Object System.Collections.Generic.List[string]
for ($Index = 0; $Index -lt $StartArgs.Count; $Index++) {
    $Arg = $StartArgs[$Index]
    $ForwardArgs.Add($Arg)
}

$DefaultJavaOptions = "-Xms2g -Xmx4g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
$JavaOptions = if ([string]::IsNullOrWhiteSpace($env:JAVA_OPTS)) { $DefaultJavaOptions } else { $env:JAVA_OPTS }
if ($env:CHATCHAT_OPENSEARCH_INSECURE_SSL -match '^(?i:true|1|yes)$' -and
    $JavaOptions -notmatch 'jdk\.internal\.httpclient\.disableHostnameVerification') {
    $JavaOptions = (($JavaOptions, "-Djdk.internal.httpclient.disableHostnameVerification=true") |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
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
$RootLibJars = Get-ChildItem -Path $LibDir -Filter "*.jar" -File -ErrorAction SilentlyContinue |
    Sort-Object Name |
    ForEach-Object { $_.FullName }
$LoaderPathItems = @($RootLibJars) + @($ExtLibDir, $DriversDir)
$LoaderPath = ($LoaderPathItems |
    Where-Object { $_ }) -join ","
$LauncherClass = "org.springframework.boot.loader.launch.PropertiesLauncher"
$ForwardArgLine = ($ForwardArgs | ForEach-Object {
    if ($_ -match '\s') {
        '"' + ($_ -replace '"', '\"') + '"'
    } else {
        $_
    }
}) -join " "
$ArgumentLine = (($JavaOptions, "-Dloader.path=`"$LoaderPath`"", "-cp", "`"$AppJar`"", $LauncherClass, "--debug=false", "--spring.config.additional-location=optional:file:$ConfigDir/", $env:APP_ARGS, $ForwardArgLine) |
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
