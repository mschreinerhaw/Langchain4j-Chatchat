[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

# Joint acceptance matrix:
# - Runtime: namespace independence, discovery-owned template binding, parameter protocol,
#   batch execution, authorization context, failure propagation and source hardcoding gate.
# - API/gateway: registration, asset/template discovery, governed invocation and HTTP live-data semantics.
# - Server: host routing, template discovery, command safety and execution result semantics.
# - Database/capability center: datasource/query registration, discovery, parameterized execution,
#   newly maintained financial templates and end-to-end Runtime evidence delivery.
$testClasses = @(
    "RuntimeDeploymentHardcodingTest",
    "InterpretationPlanWorkflowGuardTest",
    "ToolRuntimeServiceTest",
    "AgentToolArgumentResolverTest",
    "FinalSummaryWebSearchEnhancerTest",
    "InterpretationPlanRuntimeTest",
    "ApiServiceConfigServiceTest",
    "ApiAssetDiscoveryMcpToolPublisherTest",
    "ApiTemplateDiscoveryMcpToolPublisherTest",
    "ApiRequirementAnalysisMcpToolPublisherTest",
    "ApiInvokeServiceTest",
    "ApiMcpToolPublisherTest",
    "HttpEndpointConfigServiceTest",
    "HttpRequestToolServiceLivedataTest",
    "HttpRequirementAnalysisMcpToolPublisherTest",
    "ExecutionTargetRouterTest",
    "CommandTemplateServiceTest",
    "CommandTemplateDiscoveryServiceTest",
    "LinuxCommandSafetyServiceTest",
    "LinuxCommandServiceTest",
    "SqlTemplateServiceTest",
    "SqlQueryExecuteServiceTest",
    "DatabaseQueryConfigServiceTest",
    "DatabaseQueryInvokeServiceTest",
    "DatabaseQueryMcpToolPublisherTest",
    "CommandTemplateDiscoveryDatabaseQueryTest",
    "DataQueryCategoryServiceTest",
    "FinancialMarketQueryExecutorTest",
    "FinancialAnalysisQuerySampleSeederTest",
    "FinancialQueryRuntimeContractAcceptanceTest"
)

Push-Location $repositoryRoot
try {
    & mvn -pl chatchat-mcp-server -am `
        "-Dtest=$($testClasses -join ',')" `
        "-Dsurefire.failIfNoSpecifiedTests=false" `
        "-Dfrontend.skip=true" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Data capability center joint acceptance failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
