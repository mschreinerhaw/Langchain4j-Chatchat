param(
    [switch]$WithTests
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$RootPomPath = Join-Path $ProjectRoot "pom.xml"
$McpTargetDir = Join-Path $ProjectRoot "chatchat-mcp-server\target"
$RootPom = [xml](Get-Content $RootPomPath)
$Version = $RootPom.project.version
$JarPath = Join-Path $McpTargetDir "chatchat-mcp-server-$Version.jar"
$ReleaseZipPath = Join-Path $McpTargetDir "chatchat-mcp-server-$Version-release.zip"
$VerifyDir = Join-Path $McpTargetDir "package-verify"

function Assert-ChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Parent,
        [Parameter(Mandatory = $true)][string]$Child
    )

    $ParentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $ChildPath = [System.IO.Path]::GetFullPath($Child).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar

    if (-not $ChildPath.StartsWith($ParentPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate outside expected directory. Parent=$ParentPath Child=$ChildPath"
    }
}

if (!(Test-Path $RootPomPath)) {
    throw "Cannot find root pom.xml. Please run this script from the project workspace."
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Cannot resolve project version from pom.xml."
}

Push-Location $ProjectRoot
try {
    $MavenArgs = @(
        "-pl"
        "chatchat-mcp-server"
        "-am"
        "clean"
        "package"
    )

    if (-not $WithTests) {
        $MavenArgs += "-DskipTests"
    }

    Write-Host "Running Maven build: mvn $($MavenArgs -join ' ')"
    & mvn @MavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed."
    }
}
finally {
    Pop-Location
}

if (!(Test-Path $JarPath)) {
    throw "Cannot find executable jar: $JarPath"
}

if (!(Test-Path $ReleaseZipPath)) {
    throw "Cannot find release package: $ReleaseZipPath"
}

Assert-ChildPath -Parent $McpTargetDir -Child $VerifyDir
if (Test-Path $VerifyDir) {
    Remove-Item -LiteralPath $VerifyDir -Recurse -Force
}
New-Item -Path $VerifyDir -ItemType Directory -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$AppJar = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $CommonEntry = $AppJar.Entries |
        Where-Object { $_.FullName -like "BOOT-INF/lib/chatchat-common-*.jar" } |
        Select-Object -First 1

    if ($null -eq $CommonEntry) {
        throw "Executable jar does not contain chatchat-common under BOOT-INF/lib."
    }

    $CommonJarPath = Join-Path $VerifyDir ([System.IO.Path]::GetFileName($CommonEntry.FullName))
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($CommonEntry, $CommonJarPath, $true)
}
finally {
    $AppJar.Dispose()
}

$ClassCheck = & jar tf $CommonJarPath | Select-String "com/chatchat/common/tool/ToolLogSummarizer.class"
if ($null -eq $ClassCheck) {
    throw "Packaged chatchat-common is stale: missing ToolLogSummarizer.class."
}

$ReleaseCheck = & jar tf $ReleaseZipPath | Select-String "lib/app/chatchat-mcp-server.jar"
if ($null -eq $ReleaseCheck) {
    throw "Release zip does not contain lib/app/chatchat-mcp-server.jar."
}

$PluginDirectoryCheck = & jar tf $ReleaseZipPath | Select-String "lib/plugins/README.md"
if ($null -eq $PluginDirectoryCheck) {
    throw "Release zip does not contain the lib/plugins extension directory."
}

Remove-Item -LiteralPath $VerifyDir -Recurse -Force

Write-Host ""
Write-Host "MCP server package created successfully."
Write-Host "Executable jar: $JarPath"
Write-Host "Release zip: $ReleaseZipPath"
Write-Host "Verified: packaged chatchat-common contains ToolLogSummarizer.class"
Write-Host "Verified: release package contains lib/plugins"
