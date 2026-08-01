[CmdletBinding()]
param(
    [switch]$AllowConditionalSkips,
    [switch]$TencentWsaLive,
    [string]$TencentWsaQuery = "2026年8月最新人工智能行业动态与官方发布",
    [switch]$DeployedTopologyLive,
    [string]$ApiBaseUrl,
    [string]$McpBaseUrl,
    [string]$NewsBaseUrl,
    [string]$InferenceQuery = "请根据最新公开行情数据进行分析并给出有来源的建议",
    [string]$InferenceExpectedEvidence = "web_search",
    [string]$ApiAuthHeader,
    [string]$McpAuthHeader,
    [string]$NewsAuthHeader,
    [switch]$SqlMetadataLive,
    [string]$SqlMetadataAssetName,
    [string]$SqlMetadataDatabase,
    [string]$SqlMetadataTable,
    [string]$EnterpriseMetadataPath,
    [double]$MinimumLineCoverage = 0.70,
    [double]$MinimumBranchCoverage = 0.60
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if (-not $AllowConditionalSkips) {
    $missingReleaseInputs = @()
    if (-not $TencentWsaLive) { $missingReleaseInputs += "-TencentWsaLive" }
    if (-not $DeployedTopologyLive) { $missingReleaseInputs += "-DeployedTopologyLive" }
    if (-not $SqlMetadataLive) { $missingReleaseInputs += "-SqlMetadataLive" }
    if ([string]::IsNullOrWhiteSpace($EnterpriseMetadataPath)) { $missingReleaseInputs += "-EnterpriseMetadataPath" }
    if ([string]::IsNullOrWhiteSpace($env:CHATCHAT_AGENT_COVERAGE_JDBC_URL)) {
        $missingReleaseInputs += "CHATCHAT_AGENT_COVERAGE_JDBC_URL environment variable"
    }
    if ($missingReleaseInputs.Count -gt 0) {
        throw "Strict production release requires every live gate. Missing: $($missingReleaseInputs -join ', ')."
    }
}

Push-Location $repositoryRoot
try {
    $mavenArguments = @(
        "-pl", "chatchat-e2e-tests", "-am",
        "-Dfrontend.skip=true",
        "-Dchatchat.e2e.coverage-audit.strict=true"
    )
    if ($DeployedTopologyLive) {
        if ([string]::IsNullOrWhiteSpace($ApiBaseUrl) -or
            [string]::IsNullOrWhiteSpace($McpBaseUrl) -or
            [string]::IsNullOrWhiteSpace($NewsBaseUrl)) {
            throw "Deployed topology E2E requires ApiBaseUrl, McpBaseUrl and NewsBaseUrl."
        }
        $mavenArguments += "-Dchatchat.e2e.deployed-topology.live=true"
        $mavenArguments += "-Dchatchat.e2e.api-base-url=$ApiBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.mcp-base-url=$McpBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.news-base-url=$NewsBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.inference-query=$InferenceQuery"
        $mavenArguments += "-Dchatchat.e2e.inference-expected-evidence=$InferenceExpectedEvidence"
        if (-not [string]::IsNullOrWhiteSpace($ApiAuthHeader)) {
            $mavenArguments += "-Dchatchat.e2e.api-auth-header=$ApiAuthHeader"
        }
        if (-not [string]::IsNullOrWhiteSpace($McpAuthHeader)) {
            $mavenArguments += "-Dchatchat.e2e.mcp-auth-header=$McpAuthHeader"
        }
        if (-not [string]::IsNullOrWhiteSpace($NewsAuthHeader)) {
            $mavenArguments += "-Dchatchat.e2e.news-auth-header=$NewsAuthHeader"
        }
    }
    if ($TencentWsaLive) {
        if ([string]::IsNullOrWhiteSpace($env:TENCENTCLOUD_SECRET_ID) -or
            [string]::IsNullOrWhiteSpace($env:TENCENTCLOUD_SECRET_KEY)) {
            throw "Live WSA release gate requires TENCENTCLOUD_SECRET_ID and TENCENTCLOUD_SECRET_KEY."
        }
        $mavenArguments += "-Dchatchat.e2e.tencent-wsa.live=true"
        $mavenArguments += "-Dchatchat.e2e.tencent-wsa.query=$TencentWsaQuery"
    }
    if ($SqlMetadataLive) {
        if ([string]::IsNullOrWhiteSpace($SqlMetadataAssetName) -or
            [string]::IsNullOrWhiteSpace($SqlMetadataDatabase) -or
            [string]::IsNullOrWhiteSpace($SqlMetadataTable)) {
            throw "SQL metadata live gate requires asset name, database and table."
        }
        $mavenArguments += "-Dchatchat.live.metadata.test=true"
        $mavenArguments += "-Dchatchat.live.metadata.asset-name=$SqlMetadataAssetName"
        $mavenArguments += "-Dchatchat.live.metadata.database=$SqlMetadataDatabase"
        $mavenArguments += "-Dchatchat.live.metadata.table=$SqlMetadataTable"
    }
    if (-not [string]::IsNullOrWhiteSpace($EnterpriseMetadataPath)) {
        $resolvedMetadataPath = (Resolve-Path -LiteralPath $EnterpriseMetadataPath).Path
        $mavenArguments += "-Dchatchat.e2e.enterprise-metadata-path=$resolvedMetadataPath"
    }
    $mavenArguments += "verify"
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Production E2E release gate failed with exit code $LASTEXITCODE"
    }

    $reports = Get-ChildItem -Path $repositoryRoot -Directory -Filter target -Recurse |
        ForEach-Object {
            Get-ChildItem -Path $_.FullName -Recurse -File -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
        } |
        Where-Object { $_.FullName -match "(surefire|failsafe)-reports" }
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($report in $reports) {
        [xml]$suite = Get-Content -LiteralPath $report.FullName
        $tests += [int]$suite.testsuite.tests
        $failures += [int]$suite.testsuite.failures
        $errors += [int]$suite.testsuite.errors
        $skipped += [int]$suite.testsuite.skipped
    }
    Write-Host "Production E2E report: tests=$tests failures=$failures errors=$errors skipped=$skipped"
    if ($failures -ne 0 -or $errors -ne 0) {
        throw "Production E2E reports contain failures or errors"
    }
    if (-not $AllowConditionalSkips -and $skipped -ne 0) {
        throw "Production release requires zero skipped tests; skipped=$skipped. Use -AllowConditionalSkips only for non-release regression runs."
    }

    $coverageReports = Get-ChildItem -Path $repositoryRoot -Recurse -File -Filter "jacoco.xml" |
        Where-Object { $_.FullName -like "*\target\site\jacoco\jacoco.xml" }
    if ($coverageReports.Count -eq 0) {
        throw "Production release requires JaCoCo reports, but none were generated."
    }
    $lineMissed = 0L; $lineCovered = 0L; $branchMissed = 0L; $branchCovered = 0L
    foreach ($report in $coverageReports) {
        [xml]$coverage = Get-Content -LiteralPath $report.FullName
        foreach ($counter in $coverage.report.counter) {
            if ($counter.type -eq "LINE") {
                $lineMissed += [long]$counter.missed; $lineCovered += [long]$counter.covered
            } elseif ($counter.type -eq "BRANCH") {
                $branchMissed += [long]$counter.missed; $branchCovered += [long]$counter.covered
            }
        }
    }
    $lineRate = if (($lineCovered + $lineMissed) -eq 0) { 0 } else { $lineCovered / ($lineCovered + $lineMissed) }
    $branchRate = if (($branchCovered + $branchMissed) -eq 0) { 0 } else { $branchCovered / ($branchCovered + $branchMissed) }
    Write-Host ("Coverage report: line={0:P2} branch={1:P2} modules={2}" -f $lineRate, $branchRate, $coverageReports.Count)
    if ($lineRate -lt $MinimumLineCoverage -or $branchRate -lt $MinimumBranchCoverage) {
        throw ("Coverage below release threshold: line={0:P2}/{1:P2}, branch={2:P2}/{3:P2}" -f `
            $lineRate, $MinimumLineCoverage, $branchRate, $MinimumBranchCoverage)
    }
} finally {
    Pop-Location
}
