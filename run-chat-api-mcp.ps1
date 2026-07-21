param(
    [ValidateSet("start", "stop", "restart", "status")]
    [string]$Action = "restart",

    [switch]$SkipBuild,
    [switch]$WithTests,
    [switch]$Clean,

    [string]$Profile = "dev",
    [ValidateSet("h2", "mysql")]
    [string]$NewsProfile = "h2",
    [int]$ApiPort = 8080,
    [int]$McpPort = 8090,
    [int]$NewsPort = 8091,
    [int]$StartupTimeoutSeconds = 120,

    [string]$ApiArgs = "",
    [string]$McpArgs = "",
    [string]$NewsArgs = "",
    [string]$NewsInternalSecret = "chatchat_internal_default_secret"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$RootPom = Join-Path $ProjectRoot "pom.xml"
$RunRoot = Join-Path $ProjectRoot "run\local-dev"
$LogRoot = Join-Path $ProjectRoot "logs\local-dev"
$ApiPidFile = Join-Path $RunRoot "chatchat-api.pid"
$McpPidFile = Join-Path $RunRoot "chatchat-mcp-server.pid"
$NewsPidFile = Join-Path $RunRoot "chatchat-runtime-news.pid"
$ApiOutLog = Join-Path $LogRoot "chatchat-api.out.log"
$ApiErrLog = Join-Path $LogRoot "chatchat-api.err.log"
$McpOutLog = Join-Path $LogRoot "chatchat-mcp-server.out.log"
$McpErrLog = Join-Path $LogRoot "chatchat-mcp-server.err.log"
$NewsOutLog = Join-Path $LogRoot "chatchat-runtime-news.out.log"
$NewsErrLog = Join-Path $LogRoot "chatchat-runtime-news.err.log"
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

function Get-ModuleJavaProcesses {
    param([string]$Name)

    $JarPrefix = (Join-Path $ProjectRoot "$Name\target\$Name-").ToLowerInvariant()
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            ($_.Name -eq "java.exe" -or $_.Name -eq "javaw.exe") -and
            $_.CommandLine -and
            $_.CommandLine.ToLowerInvariant().Contains($JarPrefix) -and
            $_.CommandLine.ToLowerInvariant().Contains(".jar")
        } |
        ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
}

function Get-AppProcesses {
    param(
        [string]$Name,
        [string]$PidFile
    )

    $Processes = @()
    $ManagedProcess = Get-ManagedProcess -PidFile $PidFile
    if ($ManagedProcess) {
        $Processes += $ManagedProcess
    }
    $Processes += @(Get-ModuleJavaProcesses -Name $Name)
    return @($Processes | Where-Object { $_ } | Sort-Object Id -Unique)
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

    return ($Profiles -join ",")
}

function Stop-ManagedApp {
    param(
        [string]$Name,
        [string]$PidFile
    )

    $Processes = @(Get-AppProcesses -Name $Name -PidFile $PidFile)
    if ($Processes.Count -eq 0) {
        if (Test-Path $PidFile) {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        }
        Write-Host "$Name is not running."
        return
    }

    foreach ($Process in $Processes) {
        Write-Host "Stopping $Name, pid=$($Process.Id) ..."
        Stop-Process -Id $Process.Id -ErrorAction SilentlyContinue
        try {
            Wait-Process -Id $Process.Id -Timeout 30 -ErrorAction Stop
        }
        catch {
            if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) {
                Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
                Wait-Process -Id $Process.Id -Timeout 10 -ErrorAction SilentlyContinue
            }
        }
        Write-Host "$Name stopped, pid=$($Process.Id)."
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Invoke-Build {
    $MavenArgs = @(
        "-pl"
        "chatchat-api,chatchat-mcp-server,chatchat-runtime-news"
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
            if ($Clean -and (Clear-ApiStaticOutputForCleanRetry)) {
                Write-Host "Retrying Maven build after clearing chatchat-api static output ..."
                & mvn @MavenArgs
            }
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed."
        }
    }
    finally {
        Pop-Location
    }
}

function Clear-ApiStaticOutputForCleanRetry {
    $StaticOutput = Join-Path $ProjectRoot "chatchat-api\target\classes\static"
    $ApiTarget = Join-Path $ProjectRoot "chatchat-api\target"

    if (-not (Test-Path $StaticOutput)) {
        return $false
    }

    $ResolvedStaticOutput = Resolve-Path -LiteralPath $StaticOutput -ErrorAction Stop
    $ResolvedApiTarget = Resolve-Path -LiteralPath $ApiTarget -ErrorAction Stop
    if (-not $ResolvedStaticOutput.Path.StartsWith($ResolvedApiTarget.Path, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unexpected static output path: $($ResolvedStaticOutput.Path)"
    }

    try {
        Remove-Item -LiteralPath $ResolvedStaticOutput.Path -Recurse -Force -ErrorAction Stop
        Write-Host "Cleared stale static output: $($ResolvedStaticOutput.Path)"
        return $true
    }
    catch {
        Write-Warning "Could not clear stale static output '$($ResolvedStaticOutput.Path)': $($_.Exception.Message)"
        return $false
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

function Get-NewsExtraArgs {
    $Arguments = @()
    if (-not [string]::IsNullOrWhiteSpace($NewsInternalSecret)) {
        $Arguments += "--chatchat.internal-credential.secret=`"$NewsInternalSecret`""
    }
    if (-not [string]::IsNullOrWhiteSpace($NewsArgs)) {
        $Arguments += $NewsArgs
    }
    return ($Arguments -join " ")
}

function Start-ManagedApp {
    param(
        [string]$Name,
        [string]$JarPath,
        [int]$Port,
        [string]$PidFile,
        [string]$StdoutLog,
        [string]$StderrLog,
        [string]$ExtraArgs,
        [string]$LoaderPath,
        [string]$WorkingDirectory = $ProjectRoot,
        [string]$SpringProfile = $EffectiveProfile
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
    if ([string]::IsNullOrWhiteSpace($LoaderPath)) {
        $ArgumentLine = (($JavaOptions, "-jar", "`"$JarPath`"", "--debug=false", "--spring.profiles.active=$SpringProfile", "--server.port=$Port", $ExtraArgs) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
    } else {
        New-Item -ItemType Directory -Force -Path $LoaderPath | Out-Null
        $ArgumentLine = (($JavaOptions, "-Dloader.path=`"$LoaderPath`"", "-cp", "`"$JarPath`"", "org.springframework.boot.loader.launch.PropertiesLauncher", "--debug=false", "--spring.profiles.active=$SpringProfile", "--server.port=$Port", $ExtraArgs) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
    }

    Write-Host "Starting $Name on port $Port with profiles '$SpringProfile' ..."
    $Process = Start-Process `
        -FilePath $Java `
        -ArgumentList $ArgumentLine `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog `
        -WindowStyle Hidden `
        -PassThru

    Set-Content -Path $PidFile -Value $Process.Id -Encoding ASCII

    for ($Second = 1; $Second -le $StartupTimeoutSeconds; $Second++) {
        if (-not (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            Show-LogTail -Path $StdoutLog
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

    Show-LogTail -Path $StdoutLog
    Show-LogTail -Path $StderrLog
    throw "$Name did not open port $Port within $StartupTimeoutSeconds seconds."
}

function Write-ManagedStatus {
    param(
        [string]$Name,
        [string]$PidFile,
        [int]$Port
    )

    $ManagedProcess = Get-ManagedProcess -PidFile $PidFile
    $Processes = @(Get-AppProcesses -Name $Name -PidFile $PidFile)
    $PortStatus = if (Test-PortOpen -Port $Port) { "open" } else { "closed" }

    if ($ManagedProcess) {
        Write-Host "${Name}: running, pid=$($ManagedProcess.Id), port $Port is $PortStatus"
    }
    elseif ($Processes.Count -gt 0) {
        Write-Host "${Name}: running outside PID tracking, pid=$($Processes[0].Id), port $Port is $PortStatus"
    }
    else {
        Write-Host "${Name}: not running, port $Port is $PortStatus"
    }
}

Assert-ProjectRoot
New-Item -ItemType Directory -Force -Path $RunRoot, $LogRoot | Out-Null
$EffectiveProfile = Get-EffectiveProfile

switch ($Action) {
    "status" {
        Write-ManagedStatus -Name "chatchat-runtime-news" -PidFile $NewsPidFile -Port $NewsPort
        Write-ManagedStatus -Name "chatchat-mcp-server" -PidFile $McpPidFile -Port $McpPort
        Write-ManagedStatus -Name "chatchat-api" -PidFile $ApiPidFile -Port $ApiPort
        break
    }
    "stop" {
        Stop-ManagedApp -Name "chatchat-api" -PidFile $ApiPidFile
        Stop-ManagedApp -Name "chatchat-mcp-server" -PidFile $McpPidFile
        Stop-ManagedApp -Name "chatchat-runtime-news" -PidFile $NewsPidFile
        break
    }
    "restart" {
        Stop-ManagedApp -Name "chatchat-api" -PidFile $ApiPidFile
        Stop-ManagedApp -Name "chatchat-mcp-server" -PidFile $McpPidFile
        Stop-ManagedApp -Name "chatchat-runtime-news" -PidFile $NewsPidFile

        if (-not $SkipBuild) {
            Invoke-Build
        }

        $McpJar = Get-ExecutableJar -ModuleName "chatchat-mcp-server"
        $ApiJar = Get-ExecutableJar -ModuleName "chatchat-api"
        $NewsJar = Get-ExecutableJar -ModuleName "chatchat-runtime-news"

        $McpPluginPath = if ($env:CHATCHAT_MCP_PLUGIN_PATH) { $env:CHATCHAT_MCP_PLUGIN_PATH } else { Join-Path $ProjectRoot "chatchat-mcp-server/lib/plugins" }
        Start-ManagedApp -Name "chatchat-runtime-news" -JarPath $NewsJar -Port $NewsPort -PidFile $NewsPidFile -StdoutLog $NewsOutLog -StderrLog $NewsErrLog -ExtraArgs (Get-NewsExtraArgs) -WorkingDirectory (Join-Path $ProjectRoot "chatchat-runtime-news") -SpringProfile $NewsProfile
        Start-ManagedApp -Name "chatchat-mcp-server" -JarPath $McpJar -Port $McpPort -PidFile $McpPidFile -StdoutLog $McpOutLog -StderrLog $McpErrLog -ExtraArgs $McpArgs -LoaderPath $McpPluginPath
        Start-ManagedApp -Name "chatchat-api" -JarPath $ApiJar -Port $ApiPort -PidFile $ApiPidFile -StdoutLog $ApiOutLog -StderrLog $ApiErrLog -ExtraArgs $ApiArgs

        Write-Host ""
        Write-Host "Ready:"
        Write-Host "  Profiles: $EffectiveProfile"
        Write-Host "  News database profile: $NewsProfile"
        Write-Host "  News Runtime: http://localhost:$NewsPort"
        Write-Host "  API: http://localhost:$ApiPort"
        Write-Host "  MCP admin: http://localhost:$McpPort/admin"
        Write-Host "  MCP endpoint: http://localhost:$McpPort/mcp"
        break
    }
    "start" {
        if (-not $SkipBuild) {
            if (@(Get-AppProcesses -Name "chatchat-api" -PidFile $ApiPidFile).Count -gt 0 -or
                @(Get-AppProcesses -Name "chatchat-mcp-server" -PidFile $McpPidFile).Count -gt 0 -or
                @(Get-AppProcesses -Name "chatchat-runtime-news" -PidFile $NewsPidFile).Count -gt 0) {
                throw "Managed services are already running. Use -Action restart, or use -Action start -SkipBuild."
            }

            Invoke-Build
        }

        $McpJar = Get-ExecutableJar -ModuleName "chatchat-mcp-server"
        $ApiJar = Get-ExecutableJar -ModuleName "chatchat-api"
        $NewsJar = Get-ExecutableJar -ModuleName "chatchat-runtime-news"

        $McpPluginPath = if ($env:CHATCHAT_MCP_PLUGIN_PATH) { $env:CHATCHAT_MCP_PLUGIN_PATH } else { Join-Path $ProjectRoot "chatchat-mcp-server/lib/plugins" }
        Start-ManagedApp -Name "chatchat-runtime-news" -JarPath $NewsJar -Port $NewsPort -PidFile $NewsPidFile -StdoutLog $NewsOutLog -StderrLog $NewsErrLog -ExtraArgs (Get-NewsExtraArgs) -WorkingDirectory (Join-Path $ProjectRoot "chatchat-runtime-news") -SpringProfile $NewsProfile
        Start-ManagedApp -Name "chatchat-mcp-server" -JarPath $McpJar -Port $McpPort -PidFile $McpPidFile -StdoutLog $McpOutLog -StderrLog $McpErrLog -ExtraArgs $McpArgs -LoaderPath $McpPluginPath
        Start-ManagedApp -Name "chatchat-api" -JarPath $ApiJar -Port $ApiPort -PidFile $ApiPidFile -StdoutLog $ApiOutLog -StderrLog $ApiErrLog -ExtraArgs $ApiArgs

        Write-Host ""
        Write-Host "Ready:"
        Write-Host "  Profiles: $EffectiveProfile"
        Write-Host "  News database profile: $NewsProfile"
        Write-Host "  News Runtime: http://localhost:$NewsPort"
        Write-Host "  API: http://localhost:$ApiPort"
        Write-Host "  MCP admin: http://localhost:$McpPort/admin"
        Write-Host "  MCP endpoint: http://localhost:$McpPort/mcp"
        break
    }
}
