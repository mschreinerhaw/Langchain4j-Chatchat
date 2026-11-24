param(
    [string]$Image = 'llm-compatibility-gateway:1.0.0',
    [string]$OutputDirectory = 'release',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSScriptRoot
$destination = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $base $OutputDirectory }
New-Item -ItemType Directory -Force -Path $destination | Out-Null
if (-not $SkipBuild) {
    docker build --tag $Image $base
    if ($LASTEXITCODE -ne 0) { throw 'Docker build failed' }
}
$archive = Join-Path $destination 'llm-compatibility-gateway.tar'
docker image save --output $archive $Image
if ($LASTEXITCODE -ne 0) { throw 'Docker image export failed' }
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$archive.sha256", "$hash  llm-compatibility-gateway.tar" + [char]10, [Text.Encoding]::ASCII)
foreach ($file in @('docker-compose.yml', '.env.example', 'README.md')) { Copy-Item -LiteralPath (Join-Path $base $file) -Destination $destination }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-offline.ps1') -Destination $destination
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-offline.sh') -Destination $destination
Copy-Item -LiteralPath (Join-Path $base 'docs') -Destination $destination -Recurse -Force
Write-Output $archive
Write-Output "SHA-256 $hash"
