param(
    [switch]$SkipBuild,
    [switch]$SkipWebBuild,
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

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    Set-Content -Path $Path -Value $Content -Encoding UTF8
}

function Copy-TikaLibrariesToExt {
    param(
        [Parameter(Mandatory = $true)][string]$SourceJar,
        [Parameter(Mandatory = $true)][string]$DestinationDir
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -Path $DestinationDir -ItemType Directory -Force | Out-Null

    $Archive = [System.IO.Compression.ZipFile]::OpenRead($SourceJar)
    try {
        $Entries = $Archive.Entries |
            Where-Object { $_.FullName -match '^BOOT-INF/lib/tika-.*\.jar$' } |
            Sort-Object FullName

        if ($Entries.Count -eq 0) {
            Write-Warning "No Apache Tika libraries found in $SourceJar."
            return
        }

        foreach ($Entry in $Entries) {
            $DestPath = Join-Path $DestinationDir ([System.IO.Path]::GetFileName($Entry.FullName))
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($Entry, $DestPath, $true)
            Write-Host "Copied Tika library: $([System.IO.Path]::GetFileName($DestPath)) -> lib/ext"
        }
    } finally {
        $Archive.Dispose()
    }
}

if (!(Test-Path $RootPomPath)) {
    throw "Cannot find root pom.xml. Please run this script from the project workspace."
}

if (!(Test-Path (Join-Path $TemplateDir "bin"))) {
    throw "Missing packaging/bin templates."
}

if (!(Test-Path (Join-Path $TemplateDir "config\application.yml"))) {
    throw "Missing packaging/config/application.yml template."
}

if (!(Test-Path $LocalMavenRepo)) {
    New-Item -Path $LocalMavenRepo -ItemType Directory -Force | Out-Null
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
$TarGzPath = Join-Path $DistRoot "$PackageName.tar.gz"
$ZipPath = Join-Path $DistRoot "$PackageName.zip"

if (-not $SkipBuild) {
    Ensure-Java17
    $MavenArgs = @(
        "-pl"
        "chatchat-api"
        "-am"
        "-DskipTests"
        "package"
        "-Dmaven.repo.local=$LocalMavenRepo"
    )
    if ($SkipWebBuild) {
        if (!(Test-Path (Join-Path $ProjectRoot "chatchat-api\web-app\dist\index.html"))) {
            throw "SkipWebBuild requires existing chatchat-api/web-app/dist/index.html."
        }
        $MavenArgs += "-Dexec.skip=true"
        Write-Host "SkipWebBuild enabled. Reusing existing chatchat-api/web-app/dist."
    }

    Write-Host "Running Maven build: mvn $($MavenArgs -join ' ')"
    & mvn @MavenArgs
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

New-Item -Path $DistRoot -ItemType Directory -Force | Out-Null
Assert-ChildPath -Parent $DistRoot -Child $StagingRoot

if (Test-Path $StagingRoot) {
    Remove-Item -LiteralPath $StagingRoot -Recurse -Force
}

New-Item -Path $StagingRoot -ItemType Directory -Force | Out-Null
foreach ($Dir in @("bin", "config", "data", "logs", "run", "lib", "lib\app", "lib\ext", "lib\drivers")) {
    New-Item -Path (Join-Path $StagingRoot $Dir) -ItemType Directory -Force | Out-Null
}

Copy-Item -Path (Join-Path $TemplateDir "bin\*") -Destination (Join-Path $StagingRoot "bin") -Recurse -Force
Copy-Item -Path (Join-Path $TemplateDir "config\*") -Destination (Join-Path $StagingRoot "config") -Recurse -Force
Copy-Item -Path $JarFile.FullName -Destination (Join-Path $StagingRoot "lib\app\$AppName.jar") -Force
Copy-TikaLibrariesToExt -SourceJar $JarFile.FullName -DestinationDir (Join-Path $StagingRoot "lib\ext")

Write-TextFile -Path (Join-Path $StagingRoot "VERSION") -Content @"
name=$AppName
version=$Version
buildTime=$(Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
sourceJar=$($JarFile.Name)
"@

Write-TextFile -Path (Join-Path $StagingRoot "README.md") -Content @"
# ChatChat Release Package

## Directory Layout

- `bin`: startup, stop, restart, and status scripts
- `config`: externalized Spring Boot configuration
- `data`: local H2, RocksDB, and uploaded file data
- `logs`: application logs and console output
- `run`: pid file storage
- `lib/app`: executable application jar
- `lib/ext`: optional external application library jars, including copied Apache Tika jars
- `lib/drivers`: optional external JDBC driver jars

## Start

Linux:

```bash
chmod +x bin/*.sh
./bin/start.sh
```

Windows:

```powershell
.\bin\start.bat
```

## Stop And Status

```bash
./bin/status.sh
./bin/stop.sh
./bin/restart.sh
```

```powershell
.\bin\status.bat
.\bin\stop.bat
.\bin\restart.bat
```

## Production Notes

- Configure `OPENAI_API_KEY` before enabling OpenAI-compatible model calls.
- Select the runtime mode by copying an `application-*.template` preset to the active `config/application-*.yml` file.
- Apache Tika jars are copied to `lib/ext` during packaging. Put other optional non-JDBC library jars in `lib/ext`, or directly under `lib`.
- Put external JDBC driver jars in `lib/drivers`.
- Runtime JVM options can be passed with `JAVA_OPTS`; extra Spring Boot arguments can be passed with `APP_ARGS`.
"@

Write-TextFile -Path (Join-Path $StagingRoot "lib\ext\README.md") -Content @"
# Extension libraries

Put optional non-JDBC application library jars in this directory.

Apache Tika jars from the application package are copied here automatically during release packaging.

The startup scripts load jars from:

- `lib/*.jar`
- `lib/ext`
- `lib/drivers`
"@

Write-TextFile -Path (Join-Path $StagingRoot "lib\drivers\README.md") -Content @"
# JDBC drivers

Put optional external JDBC driver jars in this directory.
"@

Write-TextFile -Path (Join-Path $StagingRoot "logs\README.md") -Content @"
# logs directory

Application logs, stdout, and stderr are written here at runtime.
"@

Write-TextFile -Path (Join-Path $StagingRoot "run\README.md") -Content @"
# run directory

The startup scripts store the pid file here.
"@

Write-TextFile -Path (Join-Path $StagingRoot "data\README.md") -Content @"
# data directory

Local H2, RocksDB, and uploaded file data are stored here by default.
"@

foreach ($ArchivePath in @($TarGzPath, $ZipPath, "$TarGzPath.sha256", "$ZipPath.sha256")) {
    Assert-ChildPath -Parent $DistRoot -Child $ArchivePath
    if (Test-Path $ArchivePath) {
        Remove-Item -LiteralPath $ArchivePath -Force
    }
}

$TarCommand = Get-Command tar -ErrorAction SilentlyContinue
if ($null -ne $TarCommand) {
    Write-Host "Creating archive: $TarGzPath"
    & tar -czf $TarGzPath -C $DistRoot $PackageName
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create tar.gz package."
    }
} else {
    Write-Warning "tar command not found. Skipping tar.gz package."
}

Write-Host "Creating archive: $ZipPath"
Compress-Archive -Path $StagingRoot -DestinationPath $ZipPath -Force

foreach ($ArchivePath in @($TarGzPath, $ZipPath)) {
    if (Test-Path $ArchivePath) {
        $Hash = Get-FileHash -Algorithm SHA256 -Path $ArchivePath
        Set-Content -Path "$ArchivePath.sha256" -Value "$($Hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($ArchivePath))" -Encoding ASCII
    }
}

Write-Host ""
Write-Host "Package created successfully."
Write-Host "Staging folder: $StagingRoot"
if (Test-Path $TarGzPath) {
    Write-Host "Archive: $TarGzPath"
}
Write-Host "Archive: $ZipPath"
Write-Host "Included jar: $($JarFile.Name) -> lib/app/$AppName.jar"
