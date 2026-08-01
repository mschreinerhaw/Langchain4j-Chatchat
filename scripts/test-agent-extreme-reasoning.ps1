[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

# Agent x MCP adversarial acceptance matrix:
# - reasoning: ambiguous/poisoned discovery, invalid plans, unavailable tools and conflicting evidence;
# - repair: bounded plan rewrite, prior-observation hydration, retry, circuit breaker and safe fallback;
# - experience: resilience scoring from failure history without workflow/tool mutation;
# - integrity: required-parameter provenance, raw-target rejection, cross-asset/request isolation;
# - evidence: empty/partial/failed results, hallucination guards, deterministic fallback and honest limits;
# - MCP: API, HTTP gateway, SSH, SQL and maintained capability discovery/execution contracts.
$testClasses = @(
    "AgentPlannerTest",
    "AgentOrchestratorTest",
    "AgentAnswerFinalizerEvidenceAnswerTest",
    "AgentAnswerFinalizerTaskAssessmentTest",
    "AgentToolArgumentResolverTest",
    "AgentRuntimeGuardTest",
    "InterpretationPlanWorkflowGuardTest",
    "InterpretationPlanRewriterTest",
    "InterpretationPlanValidatorTest",
    "InterpretationPlanRuntimeTest",
    "ToolRuntimeServiceTest",
    "McpParamBindingResolverTest",
    "McpResultEvidencePolicyTest",
    "TaskResultAssessmentCompilerTest",
    "EvidenceAnswerGroundingGuardTest",
    "EvidenceGraphExecutionEngineTest",
    "EvidenceBasedTemplateCandidateEvaluatorTest",
    "DiagnosticRunTest",
    "DiagnosticRunStateMachineTest",
    "RuntimeDeploymentHardcodingTest",
    "ApiTemplateDiscoveryMcpToolPublisherTest",
    "ApiInvokeServiceTest",
    "HttpRequestToolServiceLivedataTest",
    "CommandTemplateDiscoveryServiceTest",
    "CommandTemplateDiscoveryDatabaseQueryTest",
    "LinuxCommandServiceTest",
    "SqlQueryExecuteServiceTest",
    "ExecutionTargetRouterTest",
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
        throw "Agent extreme reasoning acceptance failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
