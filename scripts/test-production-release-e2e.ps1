[CmdletBinding()]
param(
    [switch]$AllowConditionalSkips,
    [switch]$TencentWsaLive,
    [string]$TencentWsaQuery = "latest official artificial intelligence industry developments August 2026",
    [switch]$DeployedTopologyLive,
    [string]$ApiBaseUrl,
    [string]$McpBaseUrl,
    [string]$NewsBaseUrl,
    [string]$InferenceQuery = "Analyze the latest public market data and provide source-grounded recommendations",
    [string]$InferenceExpectedEvidence = "web_search",
    [string]$InferenceExpectedTool = "web_search",
    [string]$InferenceExpectedQueryArgument,
    [string]$PrePlanWorkflowQuery,
    [string]$PrePlanSkillId,
    [string]$PrePlanExpectedTools,
    [string]$PrePlanExpectedExecutionTemplates,
    [string]$PrePlanExpectedAnswerEvidence,
    [string]$PrePlanFailureQuery,
    [string]$PrePlanFailureTool,
    [string]$PrePlanFailureBlockedTools,
    [string]$PrePlanFailureExpectedEvidence,
    [int]$PrePlanRequestTimeoutMinutes = 15,
    [string]$ApiAuthHeader,
    [string]$McpAuthHeader,
    [string]$NewsAuthHeader,
    [switch]$SqlMetadataLive,
    [string]$SqlMetadataAssetName,
    [string]$SqlMetadataDatabase,
    [string]$SqlMetadataTable,
    [string]$EnterpriseMetadataPath,
    [switch]$CapacitySoakLive,
    [int]$SoakDurationSeconds = 300,
    [int]$SoakConcurrency = 8,
    [double]$SoakMinimumSuccessRate = 0.999,
    [int]$SoakMaximumP95Ms = 180000,
    [double]$MinimumQualityCasePassRate = 1.0,
    [double]$MinimumRetrievalQuality = 0.90,
    [double]$MinimumToolSelectionAccuracy = 0.95,
    [double]$MinimumParameterAccuracy = 0.95,
    [double]$MinimumEvidenceCompleteness = 0.95,
    [double]$MinimumOverallQuality = 0.95,
    [double]$MinimumLineCoverage = 0.70,
    [double]$MinimumBranchCoverage = 0.60
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

# Fail before compilation when a production resource reintroduces a repository-owned credential.
# Test fixtures may use isolated credentials, but src/main and packaged deployment configuration may not.
$credentialScanRoots = @(
    (Join-Path $repositoryRoot "packaging\config"),
    (Join-Path $repositoryRoot "chatchat-api\src\main"),
    (Join-Path $repositoryRoot "chatchat-mcp-server\src\main"),
    (Join-Path $repositoryRoot "chatchat-runtime-news\src\main"),
    (Join-Path $repositoryRoot "chatchat-license\src\main")
) | Where-Object { Test-Path -LiteralPath $_ }
$credentialPatterns = @(
    'encrypted-secret\s*:\s*ENC\(',
    '\$\{CHATCHAT_[A-Z0-9_]*(?:PASSWORD|SECRET)[A-Z0-9_]*:[^}\s$][^}]*\}'
)
$credentialFindings = @(
    Get-ChildItem -Path $credentialScanRoots -Recurse -File |
        Where-Object { $_.Extension -in @(".yml", ".yaml", ".template", ".properties", ".example") } |
        Select-String -Pattern $credentialPatterns
)
$trackedKeyFindings = @(Get-ChildItem -Path $credentialScanRoots -Recurse -File -Filter "internal-credential.key")
if ($credentialFindings.Count -gt 0 -or $trackedKeyFindings.Count -gt 0) {
    $locations = $credentialFindings |
        ForEach-Object { "$($_.Path):$($_.LineNumber)" } |
        Sort-Object -Unique
    $locations += $trackedKeyFindings | ForEach-Object { $_.FullName }
    throw "Production configuration contains repository-owned credential defaults: $($locations -join ', ')"
}

if (-not $AllowConditionalSkips) {
    $missingReleaseInputs = @()
    if (-not $TencentWsaLive) { $missingReleaseInputs += "-TencentWsaLive" }
    if (-not $DeployedTopologyLive) { $missingReleaseInputs += "-DeployedTopologyLive" }
    if (-not $CapacitySoakLive) { $missingReleaseInputs += "-CapacitySoakLive" }
    if ($missingReleaseInputs.Count -gt 0) {
        throw "Strict production release requires every live gate. Missing: $($missingReleaseInputs -join ', ')."
    }
}

Push-Location $repositoryRoot
try {
    $mavenArguments = @(
        "-pl", "chatchat-e2e-tests", "-am",
        "-Dfrontend.skip=true",
        "-Dchatchat.e2e.coverage-audit.strict=true",
        "-Dchatchat.e2e.quality.min-case-pass-rate=$MinimumQualityCasePassRate",
        "-Dchatchat.e2e.quality.min-retrieval=$MinimumRetrievalQuality",
        "-Dchatchat.e2e.quality.min-tool-selection=$MinimumToolSelectionAccuracy",
        "-Dchatchat.e2e.quality.min-parameter-accuracy=$MinimumParameterAccuracy",
        "-Dchatchat.e2e.quality.min-evidence-completeness=$MinimumEvidenceCompleteness",
        "-Dchatchat.e2e.quality.min-overall=$MinimumOverallQuality"
    )
    if ($AllowConditionalSkips) {
        $mavenArguments += "-Dchatchat.e2e.allow-conditional-skips=true"
    }
    if ($DeployedTopologyLive) {
        if ([string]::IsNullOrWhiteSpace($ApiBaseUrl) -or
            [string]::IsNullOrWhiteSpace($McpBaseUrl) -or
            [string]::IsNullOrWhiteSpace($NewsBaseUrl)) {
            throw "Deployed topology E2E requires ApiBaseUrl, McpBaseUrl and NewsBaseUrl."
        }
        if ([string]::IsNullOrWhiteSpace($PrePlanWorkflowQuery) -or
            [string]::IsNullOrWhiteSpace($PrePlanSkillId) -or
            [string]::IsNullOrWhiteSpace($PrePlanExpectedTools) -or
            [string]::IsNullOrWhiteSpace($PrePlanExpectedExecutionTemplates) -or
            [string]::IsNullOrWhiteSpace($PrePlanExpectedAnswerEvidence) -or
            [string]::IsNullOrWhiteSpace($PrePlanFailureQuery) -or
            [string]::IsNullOrWhiteSpace($PrePlanFailureTool) -or
            [string]::IsNullOrWhiteSpace($PrePlanFailureBlockedTools) -or
            [string]::IsNullOrWhiteSpace($PrePlanFailureExpectedEvidence)) {
            throw "Deployed topology E2E requires all PrePlan success and failure scenario inputs."
        }
        $mavenArguments += "-Dchatchat.e2e.deployed-topology.live=true"
        $mavenArguments += "-Dchatchat.e2e.api-base-url=$ApiBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.mcp-base-url=$McpBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.news-base-url=$NewsBaseUrl"
        $mavenArguments += "-Dchatchat.e2e.inference-query=$InferenceQuery"
        $mavenArguments += "-Dchatchat.e2e.inference-expected-evidence=$InferenceExpectedEvidence"
        $mavenArguments += "-Dchatchat.e2e.inference-expected-tool=$InferenceExpectedTool"
        if (-not [string]::IsNullOrWhiteSpace($InferenceExpectedQueryArgument)) {
            $mavenArguments += "-Dchatchat.e2e.inference-expected-query-argument=$InferenceExpectedQueryArgument"
        }
        $mavenArguments += "-Dchatchat.e2e.preplan-workflow-query=$PrePlanWorkflowQuery"
        $mavenArguments += "-Dchatchat.e2e.preplan-skill-id=$PrePlanSkillId"
        $mavenArguments += "-Dchatchat.e2e.preplan-expected-tools=$PrePlanExpectedTools"
        $mavenArguments += "-Dchatchat.e2e.preplan-expected-execution-templates=$PrePlanExpectedExecutionTemplates"
        $mavenArguments += "-Dchatchat.e2e.preplan-expected-answer-evidence=$PrePlanExpectedAnswerEvidence"
        $mavenArguments += "-Dchatchat.e2e.preplan-failure-query=$PrePlanFailureQuery"
        $mavenArguments += "-Dchatchat.e2e.preplan-failure-tool=$PrePlanFailureTool"
        $mavenArguments += "-Dchatchat.e2e.preplan-failure-blocked-tools=$PrePlanFailureBlockedTools"
        $mavenArguments += "-Dchatchat.e2e.preplan-failure-expected-evidence=$PrePlanFailureExpectedEvidence"
        $mavenArguments += "-Dchatchat.e2e.preplan-request-timeout-minutes=$PrePlanRequestTimeoutMinutes"
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
    if ($CapacitySoakLive) {
        if (-not $DeployedTopologyLive) {
            throw "Capacity/soak release gate requires -DeployedTopologyLive and ApiBaseUrl."
        }
        if ($SoakDurationSeconds -lt 300) {
            throw "Strict capacity/soak release gate requires at least 300 seconds."
        }
        $mavenArguments += "-Dchatchat.e2e.capacity-soak.live=true"
        $mavenArguments += "-Dchatchat.e2e.soak-duration-seconds=$SoakDurationSeconds"
        $mavenArguments += "-Dchatchat.e2e.soak-concurrency=$SoakConcurrency"
        $mavenArguments += "-Dchatchat.e2e.soak-min-success-rate=$SoakMinimumSuccessRate"
        $mavenArguments += "-Dchatchat.e2e.soak-max-p95-ms=$SoakMaximumP95Ms"
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
    # A release verdict must never reuse stale bytecode, test reports, or coverage data from a
    # developer's previous incremental build. In particular, compiler-level generated-code
    # metadata (for example Lombok's @Generated marker) is only reliable after a clean compile.
    $mavenArguments += "clean"
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

    # The E2E module executes production classes from multiple reactor modules. Its aggregate
    # report is the release source of truth because summing per-module unit-test reports would
    # discard that cross-module execution data. Keep the legacy reports only as a fallback for
    # diagnostics when the aggregate goal has not run.
    $aggregateCoverageReport = Join-Path $repositoryRoot "chatchat-e2e-tests\target\site\jacoco-aggregate\jacoco.xml"
    if (Test-Path -LiteralPath $aggregateCoverageReport) {
        $coverageReports = @(Get-Item -LiteralPath $aggregateCoverageReport)
        $coverageMode = "aggregate"
    } else {
        $coverageReports = @(Get-ChildItem -Path $repositoryRoot -Recurse -File -Filter "jacoco.xml" |
            Where-Object { $_.FullName -like "*\target\site\jacoco\jacoco.xml" })
        $coverageMode = "per-module fallback"
    }
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
    Write-Host ("Coverage report: line={0:P2} branch={1:P2} reports={2} mode={3}" -f `
        $lineRate, $branchRate, $coverageReports.Count, $coverageMode)
    if ($lineRate -lt $MinimumLineCoverage -or $branchRate -lt $MinimumBranchCoverage) {
        throw ("Coverage below release threshold: line={0:P2}/{1:P2}, branch={2:P2}/{3:P2}" -f `
            $lineRate, $MinimumLineCoverage, $branchRate, $MinimumBranchCoverage)
    }
} finally {
    Pop-Location
}
