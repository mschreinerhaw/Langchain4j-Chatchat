package com.chatchat.e2e;

import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.analysis.AnalysisSummaryGovernanceBridge;
import com.chatchat.agents.orchestration.analysis.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.StructuredReasoningEvidenceAdapterRegistry;
import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.answer.FinalSummaryWebSearchEnhancer;
import com.chatchat.agents.orchestration.AgentPlanner;
import com.chatchat.agents.orchestration.tool.McpAnalysisContextAdapter;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;

import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.governance.McpEvidenceGovernanceBridge;
import com.chatchat.agents.runtime.governance.McpEvidenceResult;
import com.chatchat.agents.runtime.observation.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;

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
                .filter(this::isTextSource)
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

    private boolean isTextSource(Path path) {
        String name = path == null || path.getFileName() == null
            ? ""
            : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return List.of(
            ".java", ".js", ".ts", ".vue", ".css", ".html", ".xml",
            ".yml", ".yaml", ".properties", ".json", ".md", ".sql"
        ).stream().anyMatch(name::endsWith);
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
    void internalFinancialQueriesRequireDedicatedStorageAndSuccessfulDatasetReceipts() throws IOException {
        Path root = repositoryRoot();
        String store = Files.readString(root.resolve(
            "chatchat-runtime-market/src/main/java/com/chatchat/runtime/market/storage/FinancialDataStore.java"));
        String readiness = Files.readString(root.resolve(
            "chatchat-runtime-market/src/main/java/com/chatchat/runtime/market/analysis/FinancialDatasetReadinessService.java"));
        String queryService = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/database/definition/DatabaseQueryConfigService.java"));
        String adminController = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/database/admin/DatabaseQueryAdminController.java"));
        String devConfig = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/resources/application-dev.yml"));
        String prodConfig = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/resources/application-prod.yml"));
        String governance = Files.readString(root.resolve(
            "docs/data-analysis-evidence-summary-governance.md"));

        assertThat(store).contains("isRequireDedicatedStorage()", "financialWriteStorage is unavailable");
        assertThat(readiness).contains("market_asset_catalog", "DATASET_NOT_COLLECTED",
            "FINANCIAL_STORAGE_UNAVAILABLE");
        assertThat(queryService).contains("filter(this::hasUsableDatasource)", "dataReadiness(config).ready()");
        assertThat(adminController).contains("dataAvailabilityStatus", "lastDataCollectedAt");
        assertThat(devConfig).contains("require-dedicated-storage: true");
        assertThat(prodConfig).contains("require-dedicated-storage: true");
        assertThat(governance).contains("内置数据集就绪治理", "不得互相替代");
    }

    @Test
    void summaryGovernanceProtocolCoversAllStructuredDataIncludingAssetAnalysis() throws IOException {
        Path root = repositoryRoot();
        String contextProtocol = Files.readString(root.resolve(
            "chatchat-common/src/main/java/com/chatchat/common/tool/DataAnalysisContextProtocol.java"));
        String resultFactory = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/tool/StandardToolExecutionResultFactory.java"));
        String databaseToolSpecFactory = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/database/publication/DatabaseQueryToolSpecFactory.java"));
        String financialEnrichment = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/news/financial/FinancialEnrichmentService.java"));
        String livedataRegistration = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/livedata/LivedataApiRegistrationService.java"));
        String apiServiceConfigService = Files.readString(root.resolve(
            "chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/api/ApiServiceConfigService.java"));
        String finalizer = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentAnswerFinalizer.java"));
        String observationBuilder = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/ToolObservationBuilder.java"));
        String orchestrator = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentOrchestrator.java"));
        String summaryBridge = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AnalysisSummaryGovernanceBridge.java"));
        String mcpAnalysisContextAdapter = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/McpAnalysisContextAdapter.java"));
        String summaryResult = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AnalysisSummaryResult.java"));
        String isolationScope = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/GovernanceIsolationScope.java"));
        String mcpEvidenceBridge = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/McpEvidenceGovernanceBridge.java"));
        String mcpEvidenceResult = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/McpEvidenceResult.java"));
        String toolRuntime = Files.readString(root.resolve(
            "chatchat-agents/src/main/java/com/chatchat/agents/runtime/ToolRuntimeService.java"));
        String conversationEvidenceBridge = Files.readString(root.resolve(
            "chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/ConversationEvidenceLedgerBridge.java"));
        String conversationMemory = Files.readString(root.resolve(
            "chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/ConversationMemoryService.java"));
        String interactionOrchestration = Files.readString(root.resolve(
            "chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/InteractionOrchestrationService.java"));
        String agentChatHandler = Files.readString(root.resolve(
            "chatchat-chat/src/main/java/com/chatchat/chat/interaction/service/handler/AgentChatModeHandler.java"));
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
        String governanceDocument = Files.readString(root.resolve(
            "docs/data-analysis-evidence-summary-governance.md"));

        assertThat(contextProtocol)
            .contains("SCHEMA_VERSION = \"data_analysis_context.v1\"",
                "GOVERNANCE_VERSION = \"summary_governance.v1\"",
                "DATA_IDENTITY_FOR_SUMMARY", "PRESERVE_RETURNED_FIELD_KEYS",
                "context.put(\"source\"", "context.put(\"capability\"",
                 "context.put(\"business\"", "context.put(\"schema\"",
                 "context.put(\"relationships\"", "context.put(\"semantics\"",
                 "context.put(\"quality\"", "context.put(\"analysisPolicy\"",
                 "context.put(\"extensions\"");
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
                "DataAnalysisContextProtocol.create(source, capability, business, schema, relationships,",
                "semantics, quality, analysisPolicy, extensions")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(finalizer)
            .contains("attachGovernedSummaryResult(", "answer_finalization",
                "analysisSummaryObservable", "analysisSummaryUpstreamIsolationRejected")
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
                 "mcpAnalysisContextAdapter.adapt(reference, toolMetadata, output)",
                 "mcpAnalysisContextAdapter.adaptDataset(rootAnalysisContext, dataset)",
                "analysisSummaryGovernanceBridge.requiresModelSummary(governedContext, oversized)",
                "Mandatory analysis deliverable", "ensureGovernedNarrativeAnalysis(",
                "governedNarrativeAnalysisAppended")
             .doesNotContain("api_data_identity.v1", "other API datasets");
        assertThat(mcpAnalysisContextAdapter)
            .contains("class McpAnalysisContextAdapter",
                "mcpToolMeta", "analysisContext", "analysis_context",
                "semantics", "quality", "analysisPolicy", "extensions",
                "adaptDataset(Map<String, Object> rootContext",
                "MCP_EXTENSION_KEYS", "isMcp(ToolMetadata metadata)")
            .doesNotContain("tenantId", "userId", "runId", "conversationId");
        assertThat(livedataRegistration)
            .contains("existing.getOutputSchemaJson()", "mapped.getOutputSchemaJson()",
                "apiServiceConfigService.updateDataContract(");
        assertThat(apiServiceConfigService)
            .contains("updateDataContract(String id", "current.setOutputSchemaJson(");
        assertThat(summaryBridge)
            .contains("BRIDGE_SCHEMA_VERSION = \"analysis_summary_bridge.v1\"",
                "DataAnalysisContextProtocol.GOVERNANCE_VERSION",
                 "missingSemanticSections", "semanticInferenceAllowed",
                 "analytical semantics", "analysis policy", "source extensions",
                 "requiresModelSummary(", "PRESERVE_ONLY", "DO_NOT_ANALYZE",
                "Analysis summary bridge position", "recordFrom", "recordTo", "totalRecords",
                "STRUCTURED_RECORD_FALLBACK", "finalSynthesisInstruction()")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(summaryResult)
            .contains("SCHEMA_VERSION = \"analysis_summary_result.v1\"",
                "String content", "DATASET_CHUNK", "FINAL_SYNTHESIS",
                "inputSummaryResultIds", "GOVERNED_ANALYSIS_SUMMARY",
                "RETURNED_STRUCTURED_EVIDENCE", "DataAnalysisContextProtocol.GOVERNANCE_VERSION",
                "GovernanceIsolationScope isolationScope", "requireSamePartition")
            .doesNotContain("portfolio_positions", "market_quote_daily");
        assertThat(isolationScope)
            .contains("SCHEMA_VERSION = \"governance_isolation_scope.v1\"",
                "RUNTIME_REQUEST_CONTEXT", "partitionKey()", "samePartition",
                "Cross-tenant or cross-run governance result merge rejected");
        assertThat(mcpEvidenceBridge)
            .contains("McpEvidenceResult capture(", "trustedScope(ToolRuntimeRequest request)",
                "MCP_RUNTIME_RETURNED_PAYLOAD", "crossTenantMergeAllowed", "summaryMutationAllowed");
        assertThat(mcpEvidenceResult)
            .contains("SCHEMA_VERSION = \"mcp_evidence_result.v1\"",
                "GovernanceIsolationScope isolationScope", "Object payload", "descriptor()");
        assertThat(toolRuntime)
            .contains("evidenceGovernanceBridge.capture(",
                "output.getMetadata().put(\"mcpEvidenceResult\"",
                "runtimeMetadata.put(\"mcpEvidenceResult\"",
                "McpEvidenceResult.SCHEMA_VERSION");
        assertThat(observationBuilder)
            .contains("appendMcpEvidenceGovernance", "trustedEvidenceGovernance",
                "MCP evidence governance bridge:");
        assertThat(conversationEvidenceBridge)
            .contains("SCHEMA_VERSION = \"conversation_evidence_ledger.v1\"",
                "HISTORICAL_ON_NEXT_TURN", "HISTORICAL_CONTEXT_ONLY",
                "currentFact", "revalidationRequired", "crossTenantReuseAllowed",
                "crossConversationReuseAllowed", "rawPayloadPersisted",
                "tenantId", "conversationId");
        assertThat(conversationMemory)
            .contains("conversationEvidenceLedger", "conversationEvidenceProjection(",
                "historicalEvidenceRevalidationRequired=true");
        assertThat(interactionOrchestration)
            .contains("conversationEvidence(memoryService.conversationEvidenceProjection(",
                "responseMemoryContext(response, tenantId, conversationId, requestId)");
        assertThat(agentChatHandler)
            .contains("conversationEvidenceProjection", "conversationEvidenceCurrentFact",
                "conversationEvidenceRevalidationRequired",
                "identity and lineage context only, not current-turn proof");
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
        assertThat(governanceDocument)
            .contains("# 数据分析、证据、总结与多轮会话治理规范",
                "`data_analysis_context.v1`", "`mcp_evidence_result.v1`",
                "`analysis_summary_result.v1`", "`conversation_evidence_ledger.v1`",
                "字段注释不是返回值，也不是展示标签",
                "历史证据不能冒充当前事实",
                "租户、用户、运行和会话身份只能来自可信 Runtime 请求上下文",
                "资产分析", "禁止从自然语言回答猜测恢复");
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
