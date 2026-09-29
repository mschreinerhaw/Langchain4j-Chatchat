[CmdletBinding()]
param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$tests = @(
    'DependencyRecoveryScenarioTest', 'Interpretation*Test', 'AnalysisRefinementCoordinatorTest',
    'AgentOutcomeProjectionTest', '*Workflow*Test', '*Recovery*Test', '*Lifecycle*Test',
    'AgentOrchestratorTest', 'AgentChatModeHandlerTest', 'AgentToolPolicyResolverTest',
    'DatabaseMcpToolCandidateRetrieverTest', 'EnterpriseToolRuntimePolicyProviderTest',
    'McpAuthorizationServiceTest', 'StandardMcpResultRepairerTest',
    'TemplateQueryMcpToolPublisherTest', 'BoundTemplateCandidateRetrieverTest',
    'InteractionExecutionTest', 'ApiUserRoleScheduleMcpAuthorizationIntegrationTest',
    'SkillProtocolPersistenceTest'
)
$arguments = @('-pl', 'chatchat-api,chatchat-mcp-server', '-am', 'test',
    "-Dtest=$($tests -join ',')", '-Dsurefire.failIfNoSpecifiedTests=false',
    '-Dfrontend.skip=true', '-Dfrontend.install.skip=true', '-Dmaven.test.failure.ignore=true')
if ($Offline) { $arguments = @('-o') + $arguments }
$reportDirectory = Join-Path $repositoryRoot 'target/runtime-reliability'
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$startedAt = Get-Date
$suites = @()
$failedCases = @()
Push-Location $repositoryRoot
try {
    # Continue through independent modules to inventory failures; the XML gate
    # below, not Maven's failure-ignore exit status, determines acceptance.
    $ErrorActionPreference = 'Continue'
    & mvn @arguments *> (Join-Path $reportDirectory 'maven.log')
    $mavenExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    foreach ($module in Get-ChildItem -LiteralPath $repositoryRoot -Directory -Filter 'chatchat-*') {
        $reports = Join-Path $module.FullName 'target/surefire-reports'
        if (!(Test-Path -LiteralPath $reports)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml') {
            if ($file.LastWriteTime -lt $startedAt) { continue }
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            $suite = $xml.testsuite
            $suites += [pscustomobject]@{ module = $module.Name; name = $suite.name;
                tests = [int]$suite.tests; failures = [int]$suite.failures;
                errors = [int]$suite.errors; skipped = [int]$suite.skipped }
            foreach ($case in $suite.testcase) {
                if ($case.failure -or $case.error -or $case.skipped) {
                    $failedCases += [pscustomobject]@{ suite = $suite.name; name = $case.name;
                        failure = [string]$case.failure.message; error = [string]$case.error.message;
                        skipped = [bool]$case.skipped }
                }
            }
        }
    }
    $summary = [ordered]@{ startedAt = $startedAt.ToString('o'); finishedAt = (Get-Date).ToString('o');
        mavenExit = $mavenExit; tests = ($suites | Measure-Object tests -Sum).Sum;
        failures = ($suites | Measure-Object failures -Sum).Sum;
        errors = ($suites | Measure-Object errors -Sum).Sum;
        skipped = ($suites | Measure-Object skipped -Sum).Sum; suites = $suites; failedCases = $failedCases }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportDirectory 'summary.json') -Encoding UTF8
    Write-Output "Tests=$($summary.tests), failures=$($summary.failures), errors=$($summary.errors), skipped=$($summary.skipped). Report: $reportDirectory"
    if ($mavenExit -ne 0 -or $suites.Count -eq 0 -or $summary.failures -gt 0 -or $summary.errors -gt 0 -or $summary.skipped -gt 0) {
        throw 'Runtime reliability gate failed. Inspect summary.json and maven.log; BUILD SUCCESS alone is not acceptance.'
    }
} finally { Pop-Location }
