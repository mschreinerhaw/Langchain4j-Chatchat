param(
    [string]$Query = "2026年8月最新人工智能行业动态与官方发布"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ([string]::IsNullOrWhiteSpace($env:TENCENTCLOUD_SECRET_ID) -or
    [string]::IsNullOrWhiteSpace($env:TENCENTCLOUD_SECRET_KEY)) {
    throw "Set TENCENTCLOUD_SECRET_ID and TENCENTCLOUD_SECRET_KEY before running the live WSA E2E."
}

Write-Host "Running live Tencent WSA retrieval and Agent evidence-use E2E..."
& mvn -pl chatchat-e2e-tests -am install `
    "-DskipTests" `
    "-Dfrontend.skip=true"
if ($LASTEXITCODE -ne 0) {
    throw "Tencent WSA E2E dependency build failed with exit code $LASTEXITCODE"
}

& mvn -pl chatchat-e2e-tests verify `
    "-Dfrontend.skip=true" `
    "-Dit.test=TencentWsaInferenceE2E" `
    "-Dchatchat.e2e.tencent-wsa.live=true" `
    "-Dchatchat.e2e.tencent-wsa.query=$Query" `
    "-Dchatchat.e2e.repository-root=$repositoryRoot"

if ($LASTEXITCODE -ne 0) {
    throw "Tencent WSA inference E2E failed with exit code $LASTEXITCODE"
}

Write-Host "Tencent WSA inference E2E passed. Live evidence reached Agent observations and final synthesis."
