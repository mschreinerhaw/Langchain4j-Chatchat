param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$StartArgs
)

$ErrorActionPreference = "Stop"

$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppName = "chatchat-mcp-server"
$AppJar = Join-Path $AppHome "lib/app/$AppName.jar"
$LogsDir = Join-Path $AppHome "logs"
$DriversDir = Join-Path $AppHome "lib/drivers"
$PidFile = Join-Path $LogsDir "$AppName.pid"
$StdoutLog = Join-Path $LogsDir "$AppName.out"
$StderrLog = Join-Path $LogsDir "$AppName.err"
$ConfigDir = Join-Path $AppHome "config"

New-Item -ItemType Directory -Force -Path $LogsDir, $DriversDir | Out-Null

. (Join-Path $PSScriptRoot "load-env.ps1")

$DatabaseMode = $env:CHAT_DATABASE_MODE
$SearchEngine = $env:CHATCHAT_SEARCH_ENGINE
$ForwardArgs = New-Object System.Collections.Generic.List[string]
for ($Index = 0; $Index -lt $StartArgs.Count; $Index++) {
    $Arg = $StartArgs[$Index]
    if ($Arg -match '^--database=(.+)$') {
        $DatabaseMode = $Matches[1]
        continue
    }
    if ($Arg -eq "--database") {
        if ($Index + 1 -ge $StartArgs.Count) {
            Write-Error "--database requires h2 or mysql"
            exit 1
        }
        $Index++
        $DatabaseMode = $StartArgs[$Index]
        continue
    }
    if ($Arg -eq "--mysql") {
        $DatabaseMode = "mysql"
        continue
    }
    if ($Arg -eq "--h2") {
        $DatabaseMode = "h2"
        continue
    }
    if ($Arg -match '^--search-engine=(.+)$') {
        $SearchEngine = $Matches[1]
        continue
    }
    if ($Arg -eq "--search-engine") {
        if ($Index + 1 -ge $StartArgs.Count) {
            Write-Error "--search-engine requires lucene or opensearch"
            exit 1
        }
        $Index++
        $SearchEngine = $StartArgs[$Index]
        continue
    }
    if ($Arg -eq "--lucene") {
        $SearchEngine = "lucene"
        continue
    }
    if ($Arg -eq "--opensearch") {
        $SearchEngine = "opensearch"
        continue
    }
    $ForwardArgs.Add($Arg)
}

if ($DatabaseMode) {
    switch ($DatabaseMode.Trim().ToLowerInvariant()) {
        "mysql" { $env:SPRING_PROFILES_ACTIVE = "prod,mysql" }
        "h2" { $env:SPRING_PROFILES_ACTIVE = "prod" }
        default {
            Write-Error "Unsupported database mode: $DatabaseMode. Use h2 or mysql."
            exit 1
        }
    }
} elseif (-not $env:SPRING_PROFILES_ACTIVE) {
    $env:SPRING_PROFILES_ACTIVE = "prod"
}

if ($SearchEngine) {
    switch ($SearchEngine.Trim().ToLowerInvariant()) {
        "lucene" {
            $env:CHATCHAT_SEARCH_ENGINE = "lucene"
            $env:CHATCHAT_MCP_LUCENE_ENGINE = "lucene"
        }
        "opensearch" {
            $env:CHATCHAT_SEARCH_ENGINE = "opensearch"
            $env:CHATCHAT_MCP_LUCENE_ENGINE = "opensearch"
        }
        "open-search" {
            $env:CHATCHAT_SEARCH_ENGINE = "opensearch"
            $env:CHATCHAT_MCP_LUCENE_ENGINE = "opensearch"
        }
        default {
            Write-Error "Unsupported search engine: $SearchEngine. Use lucene or opensearch."
            exit 1
        }
    }
}

$JavaOptions = $env:JAVA_OPTS
if ($env:CHATCHAT_SEARCH_ENGINE -eq "opensearch" -and $JavaOptions -notmatch "jdk\.internal\.httpclient\.disableHostnameVerification") {
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
$ArgumentList = @(
    $JavaOptions
    "-jar"
    "`"$AppJar`""
    "--debug=false"
    "--spring.config.additional-location=optional:file:$ConfigDir/"
    $env:APP_ARGS
    $ForwardArgs
) | Where-Object { $_ }

$Process = Start-Process `
    -FilePath $Java `
    -ArgumentList $ArgumentList `
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
