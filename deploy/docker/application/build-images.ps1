param(
    [Parameter(Mandatory = $true)][string]$DockerCliPath,
    [string]$Tag = "$(git rev-parse --short HEAD)-stage",
    [switch]$SkipMaven
)

Set-StrictMode -Version Latest
# Windows PowerShell 5.1 surfaces Docker progress on stderr as NativeCommandError.
$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Set-Location $repo
$buildRevision = "$(git rev-parse HEAD)-$Tag"

if (-not (Test-Path -LiteralPath $DockerCliPath -PathType Leaf)) {
    throw "Supply a Linux/amd64 Docker CLI binary compatible with the target Docker daemon."
}
if ($Tag -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$') {
    throw 'Invalid Docker tag.'
}

$contextRoot = Join-Path $repo 'target/docker-application-build'
if (-not $SkipMaven) {
    # Keep generated frontend output outside the source tree, including its versioned index.
    $frontendOutput = [IO.Path]::GetFullPath((Join-Path $contextRoot 'frontend'))
    if (-not $frontendOutput.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Frontend output must remain inside this workspace.'
    }
    Push-Location (Join-Path $repo 'chatchat-api/web-app')
    try {
        & npm.cmd ci --no-audit --no-fund --prefer-offline
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd run build -- --outDir $frontendOutput
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    & mvn -o -pl chatchat-api,chatchat-mcp-server,chatchat-runtime-news -am -Pbackend-only '-Dfrontend.skip=true' '-DskipTests' '-Dcontainer.image=true' "-Dweb.static.directory=$frontendOutput" package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
}

& (Join-Path $PSScriptRoot 'verify-web-assets.ps1') -JarPath (Join-Path $repo 'chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar')
foreach ($module in @('chatchat-api','chatchat-mcp-server','chatchat-runtime-news')) {
    & (Join-Path $PSScriptRoot 'verify-image-secrets.ps1') -JarPath (Join-Path $repo "$module/target/$module-1.0.0-SNAPSHOT.jar")
}
$apps = @(
    @{ Name = 'api'; Jar = 'chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar'; Dockerfile = 'Dockerfile' },
    @{ Name = 'mcp'; Jar = 'chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar'; Dockerfile = 'Dockerfile.mcp' },
    @{ Name = 'news'; Jar = 'chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar'; Dockerfile = 'Dockerfile' }
)
foreach ($app in $apps) {
    $context = Join-Path $contextRoot $app.Name
    New-Item -ItemType Directory -Force $context -ErrorAction Stop | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo $app.Jar) -Destination (Join-Path $context 'app.jar') -Force -ErrorAction Stop
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $app.Dockerfile) -Destination (Join-Path $context 'Dockerfile') -Force -ErrorAction Stop
    if ($app.Name -eq 'mcp') {
        $cliDestination = Join-Path $context 'docker'
        if ((Resolve-Path -LiteralPath $DockerCliPath).Path -ne $cliDestination) {
            Copy-Item -LiteralPath $DockerCliPath -Destination $cliDestination -Force -ErrorAction Stop
        }
    }
    $appPort = if ($app.Name -eq 'news') { '8091' } elseif ($app.Name -eq 'mcp') { '8090' } else { '8080' }
    & docker build --platform linux/amd64 --label "org.opencontainers.image.revision=$buildRevision" --build-arg "APP_PORT=$appPort" -t "runtime-os-$($app.Name):$Tag" $context
    if ($LASTEXITCODE -ne 0) { throw "Docker build failed for $($app.Name)." }
}

& docker build --platform linux/amd64 --build-arg "BUILD_REVISION=$buildRevision" -t "runtime-os-coding-agent:$Tag" (Join-Path $repo 'coding-agent-node')
if ($LASTEXITCODE -ne 0) { throw 'Docker build failed for Coding Agent.' }

& docker pull --platform linux/amd64 nginx:1.27.5-alpine
if ($LASTEXITCODE -ne 0) { throw 'Docker pull failed for the loopback proxy.' }

$archive = Join-Path $contextRoot "runtime-os-app-$Tag.tar"
& docker save -o $archive "runtime-os-api:$Tag" "runtime-os-mcp:$Tag" "runtime-os-news:$Tag" "runtime-os-coding-agent:$Tag" nginx:1.27.5-alpine
if ($LASTEXITCODE -ne 0) { throw 'Docker image export failed.' }
Get-FileHash -Algorithm SHA256 $archive | Select-Object Path, Hash
