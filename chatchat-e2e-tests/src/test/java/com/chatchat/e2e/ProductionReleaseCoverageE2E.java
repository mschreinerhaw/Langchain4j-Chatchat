package com.chatchat.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReleaseCoverageE2E {

    @Test
    void releaseGateOwnsEveryRequiredSystemScenarioFamily() throws IOException {
        Path root = repositoryRoot();
        String dataSuite = Files.readString(root.resolve("scripts/test-data-capability-center.ps1"));
        String reasoningSuite = Files.readString(root.resolve("scripts/test-agent-extreme-reasoning.ps1"));
        String releaseSuite = Files.readString(root.resolve("scripts/test-production-release-e2e.ps1"));
        String combined = dataSuite + reasoningSuite;

        assertThat(combined).contains(
            "AgentOrchestratorTest",
            "InterpretationPlanRuntimeTest",
            "RuntimeDeploymentHardcodingTest",
            "ApiTemplateDiscoveryMcpToolPublisherTest",
            "ApiRequirementAnalysisMcpToolPublisherTest",
            "HttpRequirementAnalysisMcpToolPublisherTest",
            "HttpRequestToolServiceLivedataTest",
            "LinuxCommandServiceTest",
            "SqlQueryExecuteServiceTest",
            "CommandTemplateDiscoveryDatabaseQueryTest",
            "FinancialQueryRuntimeContractAcceptanceTest"
        );
        assertThat(releaseSuite)
            .contains("chatchat-e2e-tests", "-am", "verify", "frontend.skip=true",
                "PrePlanWorkflowQuery", "PrePlanSkillId", "PrePlanExpectedTools",
                "PrePlanExpectedExecutionTemplates", "PrePlanExpectedAnswerEvidence",
                "PrePlanFailureQuery", "PrePlanFailureTool", "PrePlanFailureBlockedTools",
                "PrePlanFailureExpectedEvidence");
        assertThat(Files.readString(root.resolve("pom.xml")))
            .contains("<module>chatchat-e2e-tests</module>");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/e2e/ProductionWebSearchTimeoutIsolationE2E.java")))
            .contains("timeoutStormCancelsTheRuntimeChainAvoidsDatabaseWorkAndRecoversWithoutRestart",
                "nonCooperativeZombieStormStaysBoundedAndRecoversAfterDownstreamFinallyReturns",
                "McpToolConcurrencyManager", "RemoteNewsMcpToolProvider", "never()).query");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/e2e/ProductionUnpredictableUserRequestE2E.java")))
            .contains("adversarialButValidQuestionsRemainStableAndReachOnlyTheSelectedHandler",
                "invalidExtremeRequestsAreRejectedBeforeConversationPersistenceModelOrToolExecution",
                "concurrentUnpredictableQuestionsRemainResponsiveAndRequestIsolated");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/e2e/ProductionPrePlanWorkflowContinuityE2E.java")))
            .contains("prePlanToolCompletionReachesTheFirstDagDependencyWithoutBusinessHardcoding",
                "failedPrePlanStepCannotBeConvertedIntoSuccessfulDagEvidence",
                "generatedNamespace", "structuredRuntimeObservation");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/e2e/ProductionDeployedPrePlanWorkflowE2E.java")))
            .contains("userDiagnosticRequestCrossesApiAgentMcpPersistenceAndReturnsRenderableEvidence",
                "failedPrePlanToolStopsDependentsPersistsFailureAndReturnsUserFacingExplanation",
                "/api/v1/interactions/chat", "/api/v1/agent/runtime/runs/",
                "/api/v1/conversations/", "expectedTools", "expectedExecutionTemplates",
                "required previous steps")
            .doesNotContain("Mockito", "mock(", "InMemoryAgentRunStore");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/agents/orchestration/ProductionAgentRuntimeFinancialEvidenceStressE2E.java")))
            .contains("concurrentConfiguredToolParametersRemainIsolatedAndSchemaDriven",
                "concurrentExtremeEvidenceCombinationsNeverEraseUsableAnalysisOrLeakRequests",
                "UUID.randomUUID()", "requiredToolParameters", "evidenceLimitedAnalysisPreserved");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/agents/orchestration/ProductionAbbreviationRetrievalStressE2E.java")))
            .contains("modelAliasesRemainIsolatedAcrossResolverAssetAndTemplateSearchUnderConcurrency",
                "CANDIDATES_PER_KIND = 256", "CONCURRENCY = 48",
                "queryTerms", "keywords", "doesNotContainKeys(\"assetName\", \"templateId\")",
                "searchAssets", "searchTemplates", "repeat(20_000)");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/agents/orchestration/ProductionPartialEvidenceAnswerPreservationE2E.java")))
            .contains("mixedEvidencePreservesAnalysisAndExposesCoverageBoundary");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/agents/orchestration/ProductionTemplateExecutionContextContinuityE2E.java")))
            .contains("oversizedApiSshAndDatabaseTemplateResultsRemainExecutableEndToEnd",
                "api_template_query", "ssh_template_query", "database_query_template_query",
                "routingProjection", "outputTruncated");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/mcpserver/routing/ProductionRequirementAnalysisProtocolE2E.java")))
            .contains("extremePlannerPayloadIsNormalizedWithoutBusinessSpecificRules",
                "malformedEmptyAndOversizedPlannerPayloadsFailClosed",
                "apiAndHttpPublishersAreForcedToUseOneDomainNeutralProtocol");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/mcpserver/api/ProductionApiRequirementAnalysisProtocolE2E.java")))
            .contains("plannerIntentAliasCrossesNormalizationAndApiDiscovery");
        assertThat(Files.readString(root.resolve(
            "chatchat-e2e-tests/src/test/java/com/chatchat/mcpserver/ops/ProductionHttpRequirementAnalysisProtocolE2E.java")))
            .contains("plannerIntentAliasCrossesNormalizationAndHttpDiscovery");
    }

    @Test
    void runtimeContainsNoDeploymentSpecificMcpNamespaceOrMaintainedTemplateLiteral() throws IOException {
        Path root = repositoryRoot();
        Path runtimeSource = root.resolve("chatchat-agents/src/main/java");
        String forbiddenNamespace = "mcp_" + "chatchat_mcp_server_";

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> violations = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                .filter(path -> !path.toString().contains(java.io.File.separator + "target" + java.io.File.separator))
                .filter(path -> {
                    try {
                        return Files.readString(path).contains(forbiddenNamespace);
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();
            assertThat(violations).isEmpty();
        }

        try (Stream<Path> paths = Files.walk(runtimeSource)) {
            List<Path> maintainedSampleTemplateViolations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return Files.readString(path)
                            .matches("(?s).*[\\\"]sample_[a-zA-Z0-9_]+[\\\"].*");
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();
            assertThat(maintainedSampleTemplateViolations).isEmpty();
        }
    }

    @Test
    void runtimeOsCoreContainsNoBusinessDecisionLiteralsOrSimulatedAnswers() throws IOException {
        Path root = repositoryRoot();
        List<Path> runtimeCore = List.of(
            root.resolve("chatchat-agents/src/main/java/com/chatchat/agents/runtime/ToolRuntimeService.java"),
            root.resolve("chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentPlanner.java"),
            root.resolve("chatchat-agents/src/main/java/com/chatchat/agents/orchestration/FinalSummaryWebSearchEnhancer.java"),
            root.resolve("chatchat-api/src/main/java/com/chatchat/api/sidebar/SidebarCardService.java"),
            root.resolve("chatchat-api/src/main/java/com/chatchat/api/websocket/ChatWebSocketHandler.java")
        );
        List<String> forbidden = List.of(
            "forceStructured" + "FinancialData",
            "financial_data" + "_required",
            "customer" + "_detail",
            "branch" + "_summary",
            "风控" + "负责人",
            "客户" + "风险画像",
            "营业" + "部对比",
            "12.85" + "万元"
        );

        for (Path source : runtimeCore) {
            assertThat(Files.readString(source))
                .as("Runtime OS source must remain domain-neutral: %s", source)
                .doesNotContain(forbidden.toArray(String[]::new));
        }

        String legacyWebSocket = Files.readString(runtimeCore.get(4));
        assertThat(legacyWebSocket)
            .contains("@ConditionalOnProperty")
            .doesNotContain("generate" + "Response", "Thread." + "sleep");

        try (Stream<Path> paths = Files.walk(root.resolve("chatchat-agents/src/main/java"))) {
            List<Path> domainCoupledRuntimeSources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        String source = Files.readString(path).toLowerCase();
                        return source.contains("financial")
                            || source.contains("customer")
                            || source.contains("客户")
                            || source.contains("营业部")
                            || source.contains("证券");
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();
            assertThat(domainCoupledRuntimeSources)
                .as("Agent Runtime module must not contain business-domain decision vocabulary")
                .isEmpty();
        }
    }

    private Path repositoryRoot() {
        String configured = System.getProperty("chatchat.e2e.repository-root");
        Path root = configured == null || configured.isBlank()
            ? Path.of("").toAbsolutePath().normalize().getParent()
            : Path.of(configured).toAbsolutePath().normalize();
        assertThat(root.resolve("pom.xml")).isRegularFile();
        return root;
    }
}
