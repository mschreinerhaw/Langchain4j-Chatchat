param(
    [string]$SqlGlotPython,
    [switch]$Offline,
    [switch]$SkipFrontend
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not $SqlGlotPython){$SqlGlotPython=Join-Path $repo 'target/sql-toolchain-venv/Scripts/python.exe'}
$out=Join-Path $repo ('target/coding-gates/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
$report=[ordered]@{scope='Local real SDK + Docker + JDBC; deterministic model and template-source doubles';
    startedAt=(Get-Date).ToUniversalTime().ToString('o');G1='NOT_RUN';G2='NOT_RUN';G3='NOT_RUN';node='NOT_RUN';frontend='NOT_RUN';logs=$out}
function Save-Report { $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'gates.json') -Encoding UTF8 }
function Invoke-Check([string]$command,[string[]]$arguments,[string]$directory,[string]$log) {
    Push-Location -LiteralPath $directory
    $previous=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue' # Native JVM stderr warnings are not test failures.
        & $command @arguments *> (Join-Path $out $log)
        $code=$LASTEXITCODE
    } finally {$ErrorActionPreference=$previous;Pop-Location}
    return $code
}
try {
    if(-not(Test-Path -LiteralPath $SqlGlotPython)){throw "Configure -SqlGlotPython with the pinned SQLGlot 30.23.0 interpreter"}
    $dockerExit=Invoke-Check 'docker' @('info','--format','{{.ServerVersion}}') $repo 'docker-preflight.log'
    if($dockerExit -ne 0){$report.G1='ENVIRONMENT_BLOCKED';$report.G2='ENVIRONMENT_BLOCKED';$report.G3='ENVIRONMENT_BLOCKED';Save-Report;Write-Output "Docker unavailable. Evidence: $out";exit 2}
    $nodeExit=Invoke-Check 'npm.cmd' @('test') (Join-Path $repo 'coding-agent-node') 'node.log'
    if($nodeExit -ne 0){$report.node='FAILED';throw 'Node SDK regression failed'}
    $report.node='PASSED'
    $names=@('CodingCanonicalHashTest','CodingContractsTest','CodingJsonSchemaTest','CodingBudgetMathTest','HarnessCodingIntegrationTest',
        'CodingTaskPersistenceTest','CodingAnalysisServiceTest','CodingDataSupplyServiceTest','CodingFinancialResultVerifierTest',
        'CodingAnalysisDockerGateTest','CodingEvidenceDockerIntegrationTest','CodingEvidenceCollectorTest','CodingInputSnapshotServiceTest','CodingInvocationContextTest',
        'ClineCodingAdapterRecoveryTest','ClineCodingAdapterDispatchTest','ClineModelCallbackControllerTest','ClineRuntimeModelBridgeTest','ClinePythonCallbackControllerTest','ClineCallbackAuthFilterTest',
        'CodingWorkspaceServiceTest','CodingWorkspaceNodeIntegrationTest','SqlGlotToolchainTest','SqlStudioIntegrationTest','SqlStudioMetadataServiceTest','SqlStudioMetadataGatewayTest','SqlStudioGenerationToolPolicyTest','SqlOutputBundleTest',
        'PythonCodeAssistantServiceTest','RegisteredToolAnalysisOperatorTest','PythonManagedExecutionServiceTest','PythonOperationWorkspaceTest','PythonManagedInternalAuthFilterTest','LivedataSettingsConfigurationTest')
    $started=[DateTime]::UtcNow
    $maven=@('-pl','chatchat-api','-am','-Pbackend-only','-Dfrontend.skip=true','-Dcoding.realDocker=true','-Dcoding.nodeE2e=true',
        "-Dcoding.sqlglotPython=$SqlGlotPython",('-Dtest='+($names -join ',')),'-Dsurefire.failIfNoSpecifiedTests=false','test')
    if($Offline){$maven=@('-o')+$maven}
    $javaExit=Invoke-Check 'mvn.cmd' $maven $repo 'java-gates.log'
    $results=@{}
    foreach($module in @('chatchat-common','chatchat-agents','chatchat-tools','chatchat-mcp-server','chatchat-api')) {
        $folder=Join-Path $repo "$module/target/surefire-reports"
        if(Test-Path -LiteralPath $folder){foreach($file in Get-ChildItem -LiteralPath $folder -Filter 'TEST-*.xml') {
            if($file.LastWriteTimeUtc -lt $started.AddSeconds(-1)){continue}
            [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
            $suite=$xml.testsuite;$name=($suite.name -split '\.')[-1]
            if($names -contains $name){$results[$name]=[ordered]@{tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped}}
        }}
    }
    function Passed([string[]]$classes) {
        foreach($class in $classes){if(-not $results.ContainsKey($class)){return $false};$r=$results[$class];if($r.tests -lt 1 -or $r.failures -gt 0 -or $r.errors -gt 0 -or $r.skipped -gt 0){return $false}}
        return $true
    }
    $report.G1=if(Passed @('PythonManagedExecutionServiceTest','PythonOperationWorkspaceTest','PythonManagedInternalAuthFilterTest')){'PASSED'}else{'FAILED_OR_NOT_RUN'}
    $report.G2=if($report.G1 -eq 'PASSED' -and (Passed @('CodingEvidenceDockerIntegrationTest','CodingEvidenceCollectorTest'))){'PASSED'}else{'PRECONDITION_OR_TEST_FAILED'}
    $report.G3=if($report.G2 -eq 'PASSED' -and $javaExit -eq 0 -and (Passed @('CodingAnalysisDockerGateTest','CodingWorkspaceNodeIntegrationTest','CodingDataSupplyServiceTest','CodingTaskPersistenceTest','CodingAnalysisServiceTest','CodingFinancialResultVerifierTest'))){'PASSED'}else{'PRECONDITION_OR_TEST_FAILED'}
    $report.javaSuites=$results;Save-Report
    if($javaExit -ne 0 -or $report.G3 -ne 'PASSED'){throw 'Java gate failed; G3 requires current successful G1 and G2, with no skipped required tests'}
    if(-not $SkipFrontend) {
        $web=Join-Path $repo 'chatchat-api/web-app'
        $webExit=Invoke-Check 'npm.cmd' @('run','test:sql-studio','--','--maxWorkers=1','--no-file-parallelism') $web 'frontend-sql.log'
        $pythonExit=Invoke-Check 'npx.cmd' @('vitest','run','src/services/pythonCoding.test.js','src/js/views/DataScienceView.test.js','--maxWorkers=1','--no-file-parallelism') $web 'frontend-python.log'
        # Vite empties its output directory. Resolve and check the dedicated artifact path first.
        $build=[IO.Path]::GetFullPath((Join-Path $out 'web'))
        if(-not $build.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Build output escaped workspace'}
        $buildExit=Invoke-Check 'npm.cmd' @('run','build','--','--outDir',$build) $web 'frontend-build.log'
        $report.frontend=if($webExit -eq 0 -and $pythonExit -eq 0 -and $buildExit -eq 0){'PASSED'}else{'FAILED'}
        if($report.frontend -ne 'PASSED'){throw 'Frontend verification failed'}
    } else {$report.frontend='NOT_RUN_EXPLICITLY_SKIPPED'}
    $report.finishedAt=(Get-Date).ToUniversalTime().ToString('o');Save-Report
    Write-Output "G1/G2/G3 PASSED within the stated local scope. Evidence: $out"
} catch {
    $report.error=$_.Exception.Message;Save-Report;Write-Error "$($_.Exception.Message). Evidence: $out";exit 1
}
