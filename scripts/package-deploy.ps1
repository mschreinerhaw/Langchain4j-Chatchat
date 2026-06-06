param(
    [switch]$SkipBuild,
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$TemplateDir = Join-Path $ProjectRoot "packaging"
$ApiTargetDir = Join-Path $ProjectRoot "chatchat-api\target"
$RootPomPath = Join-Path $ProjectRoot "pom.xml"
$LocalMavenRepo = Join-Path $ProjectRoot ".m2\repository"

function Get-JavaMajorVersion {
    try {
        $Output = (& java -version 2>&1 | Out-String)
    } catch {
        return 0
    }

    if ($Output -match 'version "(\d+)\.(\d+)\..*"') {
        $First = [int]$Matches[1]
        $Second = [int]$Matches[2]
        if ($First -eq 1) {
            return $Second
        }
        return $First
    }

    if ($Output -match 'version "(\d+)\..*"') {
        return [int]$Matches[1]
    }

    return 0
}

function Ensure-Java17 {
    $Major = Get-JavaMajorVersion
    if ($Major -ge 17) {
        return
    }

    $JdksRoot = Join-Path $env:USERPROFILE ".jdks"
    if (!(Test-Path $JdksRoot)) {
        throw "Java 17+ is required, and no JDK directory found at $JdksRoot."
    }

    $Candidate = Get-ChildItem -Path $JdksRoot -Directory -Filter "corretto-17*" |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if ($null -eq $Candidate) {
        throw "Java 17+ is required, but no corretto-17* JDK was found under $JdksRoot."
    }

    $env:JAVA_HOME = $Candidate.FullName
    $env:Path = "$($env:JAVA_HOME)\bin;$($env:Path)"
    Write-Host "Switched JAVA_HOME to $($env:JAVA_HOME)"
}

if (!(Test-Path $RootPomPath)) {
    throw "Cannot find root pom.xml. Please run this script from the project workspace."
}

if (!(Test-Path $LocalMavenRepo)) {
    New-Item -Path $LocalMavenRepo -ItemType Directory -Force | Out-Null
}

if (!(Test-Path (Join-Path $TemplateDir "bin"))) {
    throw "Missing packaging/bin templates."
}

if (!(Test-Path (Join-Path $TemplateDir "config\application.yml"))) {
    throw "Missing packaging/config/application.yml template."
}

[xml]$RootPom = Get-Content $RootPomPath
$Version = $RootPom.project.version
if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Cannot resolve project version from pom.xml."
}

$AppName = "chatchat"
$PackageName = "$AppName-$Version"
$DistRoot = Join-Path $ProjectRoot $OutputDir
$StagingRoot = Join-Path $DistRoot $PackageName
$ArchivePath = Join-Path $DistRoot "$PackageName.tar.gz"

if (-not $SkipBuild) {
    Ensure-Java17
    Write-Host "Running Maven build: mvn clean package -DskipTests -Dmaven.repo.local=$LocalMavenRepo"
    & mvn clean package -DskipTests "-Dmaven.repo.local=$LocalMavenRepo"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed."
    }
} else {
    Write-Host "Skip build enabled. Using existing jar in chatchat-api/target."
}

if (!(Test-Path $ApiTargetDir)) {
    throw "Cannot find API target directory: $ApiTargetDir"
}

$JarFile = Get-ChildItem -Path $ApiTargetDir -Filter "chatchat-api-*.jar" -File |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $JarFile) {
    throw "Cannot find executable jar in $ApiTargetDir. Please run Maven build first."
}

if (Test-Path $StagingRoot) {
    Remove-Item -LiteralPath $StagingRoot -Recurse -Force
}

New-Item -Path $StagingRoot -ItemType Directory -Force | Out-Null
foreach ($Dir in @("bin", "config", "h2", "lib", "logs", "run")) {
    New-Item -Path (Join-Path $StagingRoot $Dir) -ItemType Directory -Force | Out-Null
}

Copy-Item -Path (Join-Path $TemplateDir "bin\*") -Destination (Join-Path $StagingRoot "bin") -Recurse -Force
Copy-Item -Path (Join-Path $TemplateDir "config\*") -Destination (Join-Path $StagingRoot "config") -Recurse -Force
Copy-Item -Path $JarFile.FullName -Destination (Join-Path $StagingRoot "lib\$($JarFile.Name)") -Force

# Extract runtime dependencies from Spring Boot fat jar: BOOT-INF/lib/*.jar
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::OpenRead($JarFile.FullName)
try {
    $DependencyEntries = $Zip.Entries | Where-Object {
        $_.FullName.StartsWith("BOOT-INF/lib/") -and $_.FullName.EndsWith(".jar")
    }

    foreach ($Entry in $DependencyEntries) {
        $DependencyName = [System.IO.Path]::GetFileName($Entry.FullName)
        if ([string]::IsNullOrWhiteSpace($DependencyName)) {
            continue
        }

        $DependencyTarget = Join-Path $StagingRoot "lib\$DependencyName"
        $EntryStream = $Entry.Open()
        try {
            $OutputStream = [System.IO.File]::Open($DependencyTarget, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
            try {
                $EntryStream.CopyTo($OutputStream)
            } finally {
                $OutputStream.Dispose()
            }
        } finally {
            $EntryStream.Dispose()
        }
    }
} finally {
    $Zip.Dispose()
}

if ($DependencyEntries.Count -eq 0) {
    Ensure-Java17
    Write-Host "No BOOT-INF/lib entries found. Fallback to Maven dependency copy."
    & mvn -pl chatchat-api -am dependency:copy-dependencies `
        -DincludeScope=runtime `
        "-DoutputDirectory=$(Join-Path $StagingRoot 'lib')" `
        -DexcludeArtifactIds=chatchat-api `
        "-Dmaven.repo.local=$LocalMavenRepo" `
        -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy runtime dependencies to lib."
    }
}

$Readme = @"
# ChatChat Deploy Package

## Directory Layout
- bin: startup and process scripts
- config: externalized config files
- lib: application jar and runtime dependencies
- logs: runtime logs
- run: pid file storage

## Start
1. cd into package root
2. run: chmod +x bin/*.sh
3. run: ./bin/start.sh

## Stop
./bin/stop.sh
"@
Set-Content -Path (Join-Path $StagingRoot "README.md") -Value $Readme -Encoding UTF8

$TarCommand = Get-Command tar -ErrorAction SilentlyContinue
if ($null -eq $TarCommand) {
    throw "tar command not found. Please install tar/bsdtar and rerun."
}

if (Test-Path $ArchivePath) {
    Remove-Item -LiteralPath $ArchivePath -Force
}

Write-Host "Creating archive: $ArchivePath"
& tar -czf $ArchivePath -C $DistRoot $PackageName
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create tar.gz package."
}

Write-Host ""
Write-Host "Package created successfully."
Write-Host "Staging folder: $StagingRoot"
Write-Host "Archive: $ArchivePath"
Write-Host "Included jar: $($JarFile.Name)"
Write-Host "Runtime dependencies in lib: $($DependencyEntries.Count)"
