param([switch]$Offline,[switch]$SkipFullSuite,[switch]$FullSuiteOnly,[string]$SqlGlotPython)
$ErrorActionPreference='Stop'
if($SkipFullSuite -and $FullSuiteOnly){throw 'Choose either selected regression or full-suite-only execution'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $repo ('target/python-analysis-gates/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$report=[ordered]@{startedAt=[DateTime]::UtcNow.ToString('o');scope='Local regression; real model and Sandbox require separate staging evidence';selected='NOT_RUN';fullBackend='NOT_RUN'}
function Run([string]$command,[string[]]$arguments,[string]$directory,[string]$log) {
    Push-Location $directory
    $previous=$ErrorActionPreference
    try {$ErrorActionPreference='Continue'; & $command @arguments *> (Join-Path $output $log);return $LASTEXITCODE}
    finally {$ErrorActionPreference=$previous;Pop-Location}
}
function Results([DateTime]$since) {
    $suites=@();$cases=@()
    foreach($folder in Get-ChildItem -LiteralPath $repo -Directory -Filter 'chatchat-*') {
        $reports=Join-Path $folder.FullName 'target/surefire-reports'
        if(-not(Test-Path $reports)){continue}
        foreach($file in Get-ChildItem $reports -Filter 'TEST-*.xml') {
            if($file.LastWriteTimeUtc -lt $since){continue}
            [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
            $suite=$xml.testsuite
            $suites+=[pscustomobject]@{name=[string]$suite.name;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped}
            foreach($case in $suite.testcase) {
                if($case.failure -or $case.error) {
                    $cases+=@{class=[string]$case.classname;name=[string]$case.name;kind=if($case.failure){'FAILURE'}else{'ERROR'}}
                }
            }
        }
    }
    return @{suites=$suites;failedCases=$cases;tests=($suites|Measure-Object tests -Sum).Sum;failures=($suites|Measure-Object failures -Sum).Sum;errors=($suites|Measure-Object errors -Sum).Sum;skipped=($suites|Measure-Object skipped -Sum).Sum}
}
$base=@('-pl','chatchat-api','-am','-Pbackend-only','-Dfrontend.skip=true')
if(-not $SqlGlotPython){$SqlGlotPython=Join-Path $repo 'target/sql-toolchain-python/python.exe'}
if(Test-Path -LiteralPath $SqlGlotPython){$base+=('-Dcoding.sqlglotPython='+[IO.Path]::GetFullPath($SqlGlotPython))}
if($Offline){$base=@('-o')+$base}
$names=@('CapabilityWorkflowProtocolTest','HarnessCodingIntegrationTest','ModelNativeAnalysisHarnessTest','BoundedAnalysisEvidenceTest','DataWorkspaceOperationsTest',
    'CodingAnalysisServiceTest','CodingTraceArtifactControllerTest','CodingInputSnapshotServiceTest','CodingChunkedSnapshotTest',
    'CodingTaskPersistenceTest','CodingDataSupplyServiceTest','ClineModelCallbackControllerTest','ClinePythonCallbackControllerTest','ProblemAnalysisLifecycleTest')
if(-not $FullSuiteOnly) {
    $start=[DateTime]::UtcNow
    $code=Run 'mvn.cmd' ($base+@(('-Dtest='+($names -join ',')),'-Dsurefire.failIfNoSpecifiedTests=false','test')) $repo 'selected-java.log'
    $report.selectedResults=Results $start
    $executed=@($report.selectedResults.suites | ForEach-Object {($_.name -split '\.')[-1]})
    $report.missingSelectedSuites=@($names | Where-Object {$_ -notin $executed})
    $report.selected=if($code -eq 0 -and $report.selectedResults.failures -eq 0 -and $report.selectedResults.errors -eq 0 -and $report.selectedResults.skipped -eq 0 -and $report.missingSelectedSuites.Count -eq 0){'PASSED'}else{'FAILED_OR_NOT_RUN'}
}
if(-not $SkipFullSuite) {
    $start=[DateTime]::UtcNow
    # Continue through test failures so dependent modules also run; the gate below still fails.
    $code=Run 'mvn.cmd' ($base+@('-fae','-Dmaven.test.failure.ignore=true','test')) $repo 'full-backend.log'
    $report.fullResults=Results $start
    $report.fullBackend=if($code -eq 0 -and $report.fullResults.tests -gt 0 -and $report.fullResults.failures -eq 0 -and $report.fullResults.errors -eq 0){'PASSED'}else{'FAILED'}
    if($FullSuiteOnly) {
        $selected=@($report.fullResults.suites | Where-Object {($_.name -split '\.')[-1] -in $names})
        $report.selectedResults=@{suites=$selected;tests=($selected|Measure-Object tests -Sum).Sum;
            failures=($selected|Measure-Object failures -Sum).Sum;errors=($selected|Measure-Object errors -Sum).Sum;
            skipped=($selected|Measure-Object skipped -Sum).Sum}
        $executed=@($selected | ForEach-Object {($_.name -split '\.')[-1]})
        $report.missingSelectedSuites=@($names | Where-Object {$_ -notin $executed})
        $report.selected=if($report.missingSelectedSuites.Count -eq 0 -and $report.selectedResults.tests -gt 0 -and
            $report.selectedResults.failures -eq 0 -and $report.selectedResults.errors -eq 0 -and
            $report.selectedResults.skipped -eq 0){'PASSED'}else{'FAILED_OR_NOT_RUN'}
        $report.selectedScope='Required suites evaluated within this full-suite run'
    }
}
$report.finishedAt=[DateTime]::UtcNow.ToString('o')
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'gates.json') -Encoding UTF8
Write-Output "Selected: $($report.selected); full backend: $($report.fullBackend). Evidence: $output"
if($report.selected -ne 'PASSED' -or (-not $SkipFullSuite -and $report.fullBackend -ne 'PASSED')){exit 1}
