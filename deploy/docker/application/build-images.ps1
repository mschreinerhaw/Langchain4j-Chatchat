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

if (-not (Test-Path -LiteralPath $DockerCliPath -PathType Leaf)) {
    throw "Supply a Linux/amd64 Docker CLI binary compatible with the target Docker daemon."
}
if ($Tag -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$') {
    throw 'Invalid Docker tag.'
}

if (-not $SkipMaven) {
    & mvn -o -pl chatchat-api,chatchat-mcp-server -am -Pbackend-only '-Dfrontend.skip=true' '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
}

$contextRoot = Join-Path $repo 'target/docker-application-build'
$apps = @(
    @{ Name = 'api'; Jar = 'chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar'; Dockerfile = 'Dockerfile' },
    @{ Name = 'mcp'; Jar = 'chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar'; Dockerfile = 'Dockerfile.mcp' }
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
    & docker build --platform linux/amd64 -t "runtime-os-$($app.Name):$Tag" $context
    if ($LASTEXITCODE -ne 0) { throw "Docker build failed for $($app.Name)." }
}

& docker build --platform linux/amd64 -t "runtime-os-coding-agent:$Tag" (Join-Path $repo 'coding-agent-node')
if ($LASTEXITCODE -ne 0) { throw 'Docker build failed for Coding Agent.' }

& docker pull --platform linux/amd64 nginx:1.27.5-alpine
if ($LASTEXITCODE -ne 0) { throw 'Docker pull failed for the loopback proxy.' }

$archive = Join-Path $contextRoot "runtime-os-app-$Tag.tar"
& docker save -o $archive "runtime-os-api:$Tag" "runtime-os-mcp:$Tag" "runtime-os-coding-agent:$Tag" nginx:1.27.5-alpine
if ($LASTEXITCODE -ne 0) { throw 'Docker image export failed.' }
Get-FileHash -Algorithm SHA256 $archive | Select-Object Path, Hash
