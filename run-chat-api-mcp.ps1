param(
    [ValidateSet("start", "stop", "restart", "status")]
    [string]$Action = "restart",

    [switch]$SkipBuild,
    [switch]$WithTests,
    [switch]$Clean,

    [string]$Profile = "dev",
    [int]$ApiPort = 8080,
    [int]$McpPort = 8090,
    [int]$StartupTimeoutSeconds = 120,

    [ValidateSet("", "h2", "mysql")]
    [string]$Database = "",

    [ValidateSet("", "lucene", "opensearch")]
    [string]$SearchEngine = "",

    [string]$ApiArgs = "",
    [string]$McpArgs = ""
)

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$RootPom = Join-Path $ProjectRoot "pom.xml"
$RunRoot = Join-Path $ProjectRoot "run\local-dev"
$LogRoot = Join-Path $ProjectRoot "logs\local-dev"
$ApiPidFile = Join-Path $RunRoot "chatchat-api.pid"
$McpPidFile = Join-Path $RunRoot "chatchat-mcp-server.pid"
$ApiOutLog = Join-Path $LogRoot "chatchat-api.out.log"
$ApiErrLog = Join-Path $LogRoot "chatchat-api.err.log"
$McpOutLog = Join-Path $LogRoot "chatchat-mcp-server.out.log"
$McpErrLog = Join-Path $LogRoot "chatchat-mcp-server.err.log"
$EffectiveProfile = $null

function Assert-ProjectRoot {
    if (-not (Test-Path $RootPom)) {
        throw "Cannot find pom.xml at $RootPom. Run this script from the Langchain4j-Chatchat project root."
    }
}

function Get-JavaCommand {
    $Candidates = @()
    foreach ($HomeVar in @("JAVA_HOME", "JAVA17_HOME", "JDK_HOME")) {
        $JavaHomeValue = [Environment]::GetEnvironmentVariable($HomeVar)
        if ($JavaHomeValue) {
            $Candidates += Join-Path $JavaHomeValue "bin\java.exe"
        }
    }
    $Candidates += @(
        "$env:USERPROFILE\.jdks\corretto-17.0.15\bin\java.exe",
        "$env:USERPROFILE\.jdks\corretto-21.0.8\bin\java.exe"
    )
    $Candidates += Get-ChildItem -Path "C:\Program Files\Java", "$env:USERPROFILE\.jdks" -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName

    foreach ($Candidate in ($Candidates | Where-Object { $_ } | Select-Object -Unique)) {
        if ((Test-Path $Candidate) -and (Get-JavaMajorVersion -JavaPath $Candidate) -ge 17) {
            return $Candidate
        }
    }

    $PathJava = "java"
    if ((Get-JavaMajorVersion -JavaPath $PathJava) -ge 17) {
        return $PathJava
    }

    throw "Java 17+ is required. Current JAVA_HOME is '$env:JAVA_HOME'. Set JAVA_HOME to a JDK 17+ installation."
}

function Get-JavaMajorVersion {
    param([string]$JavaPath)

    try {
        if ($JavaPath -eq "java") {
            $VersionOutput = cmd /c "java -version 2>&1"
        }
        else {
            $VersionOutput = cmd /c "`"$JavaPath`" -version 2>&1"
        }
        $VersionOutput = $VersionOutput | Out-String
    }
    catch {
        return 0
    }

    if ($VersionOutput -match 'version "1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($VersionOutput -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    if ($VersionOutput -match 'openjdk (\d+)') {
        return [int]$Matches[1]
    }
    return 0
}

function Get-JavaHomeFromCommand {
    param([string]$JavaPath)

    if ($JavaPath -eq "java") {
        return $null
    }
    return Split-Path -Parent (Split-Path -Parent $JavaPath)
}

function Get-ManagedProcess {
    param([string]$PidFile)

    if (-not (Test-Path $PidFile)) {
        return $null
    }

    $PidValue = Get-Content $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $PidValue) {
        return $null
    }

    return Get-Process -Id $PidValue -ErrorAction SilentlyContinue
}

function Test-PortOpen {
    param([int]$Port)

    $Client = [System.Net.Sockets.TcpClient]::new()
    try {
        $ConnectTask = $Client.ConnectAsync("127.0.0.1", $Port)
        if (-not $ConnectTask.Wait(500)) {
            return $false
        }

        return $Client.Connected
    }
    catch {
        return $false
    }
    finally {
        $Client.Dispose()
    }
}

function Get-EffectiveProfile {
    $Profiles = New-Object System.Collections.Generic.List[string]
    foreach ($Item in ($Profile -split ",")) {
        $Value = $Item.Trim()
        if ($Value -and -not $Profiles.Contains($Value)) {
            $Profiles.Add($Value)
        }
    }

    if ($Profiles.Count -eq 0) {
        $Profiles.Add("dev")
    }

    if ($Database) {
        while ($Profiles.Contains("mysql")) {
            [void]$Profiles.Remove("mysql")
        }
        if ($Database -eq "mysql") {
            $Profiles.Add("mysql")
        }
    }

    if ($SearchEngine) {
        while ($Profiles.Contains("search-lucene")) {
            [void]$Profiles.Remove("search-lucene")
        }
        while ($Profiles.Contains("search-opensearch")) {
            [void]$Profiles.Remove("search-opensearch")
        }
        $Profiles.Add("search-$SearchEngine")
    }

    return ($Profiles -join ",")
}

function Stop-ManagedApp {
    param(
        [string]$Name,
        [string]$PidFile
    )

    $Process = Get-ManagedProcess -PidFile $PidFile
    if (-not $Process) {
        if (Test-Path $PidFile) {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        }
        Write-Host "$Name is not running."
        return
    }

    Write-Host "Stopping $Name, pid=$($Process.Id) ..."
    Stop-Process -Id $Process.Id -ErrorAction SilentlyContinue
    try {
        Wait-Process -Id $Process.Id -Timeout 30 -ErrorAction Stop
    }
    catch {
        if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "$Name stopped."
}

function Invoke-Build {
    $MavenArgs = @(
        "-pl"
        "chatchat-api,chatchat-mcp-server"
        "-am"
    )

    if ($Clean) {
        $MavenArgs += "clean"
    }

    $MavenArgs += "package"

    if (-not $WithTests) {
        $MavenArgs += "-DskipTests"
    }

    Push-Location $ProjectRoot
    try {
        $Java = Get-JavaCommand
        $JavaHome = Get-JavaHomeFromCommand -JavaPath $Java
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
            $env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path
            Write-Host "Using JAVA_HOME=$JavaHome"
        }
        Write-Host "Building modules: mvn $($MavenArgs -join ' ')"
        & mvn @MavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed."
        }
    }
    finally {
        Pop-Location
    }
}

function Get-ExecutableJar {
    param([string]$ModuleName)

    $TargetDir = Join-Path $ProjectRoot "$ModuleName\target"
    $Jar = Get-ChildItem -Path $TargetDir -Filter "$ModuleName-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -notlike "*.original" -and
            $_.Name -notlike "*-sources.jar" -and
            $_.Name -notlike "*-javadoc.jar"
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $Jar) {
        throw "Cannot find executable jar for $ModuleName under $TargetDir. Build the module first."
    }

    return $Jar.FullName
}

function Show-LogTail {
    param([string]$Path)

    if (Test-Path $Path) {
        Write-Host "Last log lines from $Path"
        Get-Content -Path $Path -Tail 40 | ForEach-Object { Write-Host $_ }
    }
}

function Start-ManagedApp {
    param(
        [string]$Name,
        [string]$JarPath,
        [int]$Port,
        [string]$PidFile,
        [string]$StdoutLog,
        [string]$StderrLog,
        [string]$ExtraArgs
    )

    $ExistingProcess = Get-ManagedProcess -PidFile $PidFile
    if ($ExistingProcess) {
        Write-Host "$Name is already running, pid=$($ExistingProcess.Id)."
        return
    }

    if (Test-PortOpen -Port $Port) {
        throw "$Name cannot start because port $Port is already in use."
    }

    if (Test-Path $PidFile) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    }

    $Java = Get-JavaCommand
    $JavaOptions = $env:JAVA_OPTS
    if ($EffectiveProfile -match "(^|,)search-opensearch(,|$)" -and $JavaOptions -notmatch "jdk\.internal\.httpclient\.disableHostnameVerification") {
        $JavaOptions = (($JavaOptions, "-Djdk.internal.httpclient.disableHostnameVerification=true") |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
    }
    $ArgumentLine = (($JavaOptions, "-jar", "`"$JarPath`"", "--debug=false", "--spring.profiles.active=$EffectiveProfile", "--server.port=$Port", $ExtraArgs) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "

    Write-Host "Starting $Name on port $Port with profiles '$EffectiveProfile' ..."
    $Process = Start-Process `
        -FilePath $Java `
        -ArgumentList $ArgumentLine `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII

    for ($Second = 1; $Second -le $StartupTimeoutSeconds; $Second++) {
        if (-not (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            Show-LogTail -Path $StderrLog
            throw "$Name exited before port $Port became available."
        }

        if (Test-PortOpen -Port $Port) {
            Write-Host "$Name started, pid=$($Process.Id), port=$Port"
            Write-Host "stdout: $StdoutLog"
            Write-Host "stderr: $StderrLog"
            return
        }

        Start-Sleep -Seconds 1
    }

    Show-LogTail -Path $StderrLog
    throw "$Name did not open port $Port within $StartupTimeoutSeconds seconds."
}

function Write-ManagedStatus {
    param(
        [string]$Name,
        [string]$PidFile,
        [int]$Port
    )

    $Process = Get-ManagedProcess -PidFile $PidFile
    $PortStatus = if (Test-PortOpen -Port $Port) { "open" } else { "closed" }

    if ($Process) {
        Write-Host "${Name}: running, pid=$($Process.Id), port $Port is $PortStatus"
    }
    else {
        Write-Host "${Name}: not managed by this script, port $Port is $PortStatus"
    }
}

Assert-ProjectRoot
New-Item -ItemType Directory -Force -Path $RunRoot, $LogRoot | Out-Null
$EffectiveProfile = Get-EffectiveProfile

switch ($Action) {
    "status" {
        Write-ManagedStatus -Name "chatchat-mcp-server" -PidFile $McpPidFile -Port $McpPort
        Write-ManagedStatus -Name "chatchat-api" -PidFile $ApiPidFile -Port $ApiPort
        break
    }
    "stop" {
        Stop-ManagedApp -Name "chatchat-api" -PidFile $ApiPidFile
        Stop-ManagedApp -Name "chatchat-mcp-server" -PidFile $McpPidFile
        break
    }
    "restart" {
        Stop-ManagedApp -Name "chatchat-api" -PidFile $ApiPidFile
        Stop-ManagedApp -Name "chatchat-mcp-server" -PidFile $McpPidFile

        if (-not $SkipBuild) {
            Invoke-Build
        }

        $McpJar = Get-ExecutableJar -ModuleName "chatchat-mcp-server"
        $ApiJar = Get-ExecutableJar -ModuleName "chatchat-api"

        Start-ManagedApp -Name "chatchat-mcp-server" -JarPath $McpJar -Port $McpPort -PidFile $McpPidFile -StdoutLog $McpOutLog -StderrLog $McpErrLog -ExtraArgs $McpArgs
        Start-ManagedApp -Name "chatchat-api" -JarPath $ApiJar -Port $ApiPort -PidFile $ApiPidFile -StdoutLog $ApiOutLog -StderrLog $ApiErrLog -ExtraArgs $ApiArgs

        Write-Host ""
        Write-Host "Ready:"
        Write-Host "  Profiles: $EffectiveProfile"
        Write-Host "  API: http://localhost:$ApiPort"
        Write-Host "  MCP admin: http://localhost:$McpPort/admin"
        Write-Host "  MCP endpoint: http://localhost:$McpPort/mcp"
        break
    }
    "start" {
        if (-not $SkipBuild) {
            if ((Get-ManagedProcess -PidFile $ApiPidFile) -or (Get-ManagedProcess -PidFile $McpPidFile)) {
                throw "Managed services are already running. Use -Action restart, or use -Action start -SkipBuild."
            }

            Invoke-Build
        }

        $McpJar = Get-ExecutableJar -ModuleName "chatchat-mcp-server"
        $ApiJar = Get-ExecutableJar -ModuleName "chatchat-api"

        Start-ManagedApp -Name "chatchat-mcp-server" -JarPath $McpJar -Port $McpPort -PidFile $McpPidFile -StdoutLog $McpOutLog -StderrLog $McpErrLog -ExtraArgs $McpArgs
        Start-ManagedApp -Name "chatchat-api" -JarPath $ApiJar -Port $ApiPort -PidFile $ApiPidFile -StdoutLog $ApiOutLog -StderrLog $ApiErrLog -ExtraArgs $ApiArgs

        Write-Host ""
        Write-Host "Ready:"
        Write-Host "  Profiles: $EffectiveProfile"
        Write-Host "  API: http://localhost:$ApiPort"
        Write-Host "  MCP admin: http://localhost:$McpPort/admin"
        Write-Host "  MCP endpoint: http://localhost:$McpPort/mcp"
        break
    }
}
