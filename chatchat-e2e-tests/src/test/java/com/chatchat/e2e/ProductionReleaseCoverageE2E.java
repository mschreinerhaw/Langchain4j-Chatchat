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
            "FinancialMarketQueryExecutorTest",
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

    @Test
    void runtimeReleaseGatePreservesSingleDiscoveryAndCurrentTurnEvidenceIsolation() throws IOException {
        Path root = repositoryRoot();
        String runtime = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java"));
        String orchestrator = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentOrchestrator.java"));
        String runtimeTests = Files.readString(root.resolve(
            "chatchat-agents/src/test/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntimeTest.java"));
        String orchestratorTests = Files.readString(root.resolve(
            "chatchat-agents/src/test/java/com/chatchat/agents/orchestration/AgentOrchestratorTest.java"));

        assertThat(runtime)
            .contains("runtimeOwnsTemplateBatch", "? completed", ": resolveTemplateContractFromMcp");
        assertThat(orchestrator)
            .contains("removeUnsupportedCurrentTurnDocumentReferences",
                "currentTurnDocumentReferenceGuardApplied");
        assertThat(runtimeTests)
            .contains("Runtime-owned diagnostic execution must reuse the completed discovery result");
        assertThat(orchestratorTests)
            .contains("finalSynthesisRemovesExternalDocumentReferencesAbsentFromCurrentTurnEvidence");
    }

    @Test
    void summaryGovernanceProtocolCoversAllStructuredDataIncludingAssetAnalysis() throws IOException {
        Path root = repositoryRoot();
        String contextProtocol = Files.readString(root.resolve(
            "chatchat-common/src/main/java/com/chatchat/common/tool/DataAnalysisContextProtocol.java"));
        String resultFactory = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/tool/StandardToolExecutionResultFactory.java"));
        String databaseToolSpecFactory = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/database/DatabaseQueryToolSpecFactory.java"));
        String financialEnrichment = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/news/FinancialEnrichmentService.java"));
        String finalizer = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentAnswerFinalizer.java"));
        String observationBuilder = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/ToolObservationBuilder.java"));
        String orchestrator = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentOrchestrator.java"));
        String summaryBridge = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AnalysisSummaryGovernanceBridge.java"));
        String summaryResult = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AnalysisSummaryResult.java"));
        String structuredAdapter = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/StructuredReasoningEvidenceAdapterRegistry.java"));
        String factGrounding = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/AgentRuntimeFactGroundingContract.java"));
        String renderer = Files.readString(root.resolve(
            "chatchat-api/web-app/src/js/components/VisualizationRenderer.js"));
        String rendererTemplate = Files.readString(root.resolve(
            "chatchat-api/web-app/src/components/VisualizationRenderer.vue"));
        String protocolTest = Files.readString(root.resolve(
            "chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/tool/StandardToolExecutionResultFactoryTest.java"));
        String presentationTest = Files.readString(root.resolve(
            "chatchat-agents/src/test/java/com/chatchat/agents/orchestration/AgentAnswerFinalizerEvidenceAnswerTest.java"));

        assertThat(contextProtocol)
            .contains("SCHEMA_VERSION = \"data_analysis_context.v1\"",
                "GOVERNANCE_VERSION = \"summary_governance.v1\"",
                "DATA_IDENTITY_FOR_SUMMARY", "PRESERVE_RETURNED_FIELD_KEYS",
                "context.put(\"source\"", "context.put(\"capability\"",
                "context.put(\"business\"", "context.put(\"schema\"",
                "context.put(\"relationships\"");
        assertThat(resultFactory)
            .contains("DataAnalysisContextProtocol.create(",
                "databaseQueryAnalysisContext(config, resultData, toolName)")
            .doesNotContain("api_data_identity.v1", "database_query_analysis_context.v1",
                "\"columnMetadata\", fieldMetadata", "\"label\", firstText");
        assertThat(databaseToolSpecFactory)
            .contains("DataAnalysisContextProtocol.create(")
            .doesNotContain("businessGroupCode", "businessGroupName", "businessGroupDescription");
        assertThat(financialEnrichment)
            .contains("financialAnalysisContext(dataset, asset, result)",
                "financialAnalysisContext(dataset, map(result.get(\"asset\")), result)",
                "DataAnalysisContextProtocol.create(source, capability, business, schema, relationships)")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(finalizer)
            .doesNotContain("applyConfiguredColumnLabels", "configuredColumnLabelsApplied",
                "columnDefinitions(columns, data)");
        assertThat(observationBuilder)
            .contains("\"analysisContext\", output.get(\"analysisContext\")",
                "Data analysis context (semantic input, not returned values or presentation labels)")
            .doesNotContain("api_data_identity.v1");
        assertThat(orchestrator)
            .contains("analysisSummaryGovernanceBridge.finalSynthesisInstruction()",
                "analysisSummaryGovernanceBridge.govern(",
                "analysisSummaryGovernanceBridge.position(",
                "analysisSummaryGovernanceBridge.summarize(",
                "analysisSummaryGovernanceBridge.ledger(",
                "governedFinalSummaryResult(", "analysisSummaryResult",
                "analysisContext(output)", "structuredDatasetRecordSets(output, reference)")
            .doesNotContain("api_data_identity.v1", "other API datasets");
        assertThat(summaryBridge)
            .contains("BRIDGE_SCHEMA_VERSION = \"analysis_summary_bridge.v1\"",
                "DataAnalysisContextProtocol.GOVERNANCE_VERSION",
                "missingSemanticSections", "semanticInferenceAllowed",
                "Analysis summary bridge position", "recordFrom", "recordTo", "totalRecords",
                "STRUCTURED_RECORD_FALLBACK", "finalSynthesisInstruction()")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(summaryResult)
            .contains("SCHEMA_VERSION = \"analysis_summary_result.v1\"",
                "String content", "DATASET_CHUNK", "FINAL_SYNTHESIS",
                "inputSummaryResultIds", "GOVERNED_ANALYSIS_SUMMARY",
                "RETURNED_STRUCTURED_EVIDENCE", "DataAnalysisContextProtocol.GOVERNANCE_VERSION")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(structuredAdapter)
            .contains("analysisContexts", "summary-governance input",
                "not observed data or a presentation-label mapping");
        assertThat(factGrounding)
            .contains("Summary-governance contract (summary_governance.v1)",
                "Apply this contract uniformly across API, database, asset analysis, and future data structures");
        assertThat(renderer)
            .contains("this.columns.map(csvCell)")
            .doesNotContain("displayColumnLabel", "columnDescription");
        assertThat(rendererTemplate)
            .contains("{{ column }}")
            .doesNotContain("displayColumnLabel(column)", "columnDescription(column)");
        assertThat(protocolTest)
            .contains("apiExecutionUsesStandardEnvelopeAndKeepsUpstreamCompletenessUnknown",
                "data_analysis_context.v1", "contextCapability", "contextBusiness");
        assertThat(presentationTest)
            .contains("apiOutputSchemaDescriptionsDoNotReplaceReturnedFieldNames",
                "UNCONFIGURED");
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
