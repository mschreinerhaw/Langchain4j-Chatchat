<#
.SYNOPSIS
Build unmodified Maven release artifacts for API, MCP and News.
.DESCRIPTION
Uses the normal frontend build and Spring Boot packaging. Never patches JARs or
merges historical static assets. The output is outside Maven target directories
so a subsequent clean cannot remove the release manifest or rollback evidence.
.EXAMPLE
./scripts/build-production.ps1 -JavaHome C:/Java/jdk-17
#>
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = "mvn.cmd",
    [string]$OutputDirectory,
    [switch]$WithTests
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ($JavaHome) {
    $env:JAVA_HOME = (Resolve-Path $JavaHome).Path
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path (Split-Path $ProjectRoot -Parent) (
        "deployment-artifacts/release-" + (Get-Date -Format "yyyyMMdd-HHmmss")
    )
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if ($OutputDirectory -match '[/\\]target([/\\]|$)') {
    throw "Release evidence must be outside Maven target directories."
}
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Choose a new output directory to preserve existing release evidence: $OutputDirectory"
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
[xml]$Pom = Get-Content (Join-Path $ProjectRoot "pom.xml")
$Version = $Pom.project.version
$MavenArgs = @("-pl", "chatchat-api,chatchat-mcp-server,chatchat-runtime-news", "clean", "-am", "package")
if (-not $WithTests) { $MavenArgs += "-DskipTests" }

Push-Location $ProjectRoot
try {
    $Head = (& git rev-parse HEAD | Out-String).Trim()
    $Changes = @(& git status --porcelain)
    $JavaVersion = (& java --version | Out-String).Trim()
    $MavenVersion = (& $MavenCommand -version 2>&1 | Out-String).Trim()
    $NodeVersion = (& node --version | Out-String).Trim()
    $NpmVersion = (& npm.cmd --version | Out-String).Trim()
    Write-Host "Running: $MavenCommand $($MavenArgs -join ' ')"
    # Windows PowerShell 5 maps native stderr (including npm warnings) to error
    # records. Use the process exit code, rather than treating warnings as failure.
    $SavedErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $MavenCommand @MavenArgs 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath (Join-Path $OutputDirectory "build.log")
        $BuildExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $SavedErrorPreference }
    if ($BuildExitCode -ne 0) { throw "Maven build failed; see $OutputDirectory/build.log" }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Artifacts = @()
    $ReleaseArchives = @()
    foreach ($Module in @("chatchat-api", "chatchat-mcp-server", "chatchat-runtime-news")) {
        $Source = Join-Path $ProjectRoot "$Module/target/$Module-$Version.jar"
        $Archive = [System.IO.Compression.ZipFile]::OpenRead($Source)
        try {
            if (-not ($Archive.Entries | Where-Object { $_.FullName -eq "BOOT-INF/classes/" })) {
                throw "Expected a Spring Boot executable JAR: $Source"
            }
            $StaticEntries = @($Archive.Entries | Where-Object { $_.FullName.StartsWith("BOOT-INF/classes/static/") -and $_.Name })
            $Libraries = @($Archive.Entries | Where-Object { $_.FullName.StartsWith("BOOT-INF/lib/") -and $_.Name })
            $StaticBytes = [long]($StaticEntries | Measure-Object -Property Length -Sum).Sum
        } finally { $Archive.Dispose() }
        $Destination = Join-Path $OutputDirectory "$Module.jar"
        Copy-Item -LiteralPath $Source -Destination $Destination
        $Hash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash) {
            throw "Artifact copy checksum mismatch: $Module"
        }
        $Artifacts += [ordered]@{
            module = $Module; source = $Source; file = $Destination
            bytes = (Get-Item -LiteralPath $Source).Length; sha256 = $Hash
            staticFiles = $StaticEntries.Count; staticBytes = $StaticBytes; libraries = $Libraries.Count
        }
        $ReleaseZip = Join-Path $ProjectRoot "$Module/target/$Module-$Version-release.zip"
        Copy-Item -LiteralPath $ReleaseZip -Destination $OutputDirectory
        $ReleaseArchives += [ordered]@{
            file = [System.IO.Path]::GetFileName($ReleaseZip)
            bytes = (Get-Item -LiteralPath $ReleaseZip).Length
            sha256 = (Get-FileHash -LiteralPath $ReleaseZip -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    [ordered]@{
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        head = $Head; workingTreeChanges = $Changes
        command = "$MavenCommand $($MavenArgs -join ' ')"
        java = $JavaVersion; maven = $MavenVersion; node = $NodeVersion; npm = $NpmVersion
        artifacts = $Artifacts
        releaseArchives = $ReleaseArchives
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory "manifest.json") -Encoding UTF8
    Write-Host "Standard Maven artifacts and SHA-256 manifest: $OutputDirectory"
} finally { Pop-Location }
