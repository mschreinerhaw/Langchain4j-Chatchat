$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    $archive = 'llm-compatibility-gateway.tar'
    $expected = ((Get-Content -LiteralPath "$archive.sha256" -TotalCount 1) -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'SHA-256 mismatch' }
    if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) { throw 'Create .env from .env.example before installation' }
    docker image load --input $archive
    if ($LASTEXITCODE -ne 0) { throw 'Docker image load failed' }
    docker compose up --no-build --pull never -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Gateway startup failed' }
} finally { Pop-Location }
