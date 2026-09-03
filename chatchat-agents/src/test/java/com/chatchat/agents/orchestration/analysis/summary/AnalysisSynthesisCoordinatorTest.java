package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLifecycle;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLineageGraph;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisSynthesisCoordinatorTest {

    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");

    @Test
    void ownsCrossDatasetReductionAndCompletesLifecycleAfterWorkerReconciliation() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        DeterministicInsightEngine insightEngine = mock(DeterministicInsightEngine.class);
        DeterministicInsightEngine.Result bundleResult = new DeterministicInsightEngine.Result(
            DeterministicInsightEngine.RESULT_VERSION, "executed", "bundle", "1",
            scope.toMap(), List.of(), List.of());
        when(insightEngine.analyzeBundle(any(), any())).thenReturn(bundleResult);
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            adapter, "agentRunId", mock(AnalysisSummaryGovernanceCoordinator.class),
            insightEngine, new AnswerCandidateCollector(), new HierarchicalAnalysisReducer());
        AnalysisSummaryResult datasetSummary = datasetSummary("dataset-a", "governed result");
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            new DatasetRelationshipPlan.Dataset("dataset-a", Map.of())));
        DataAnalysisLifecycle lifecycle = DataAnalysisLifecycle.begin("analysis-a", 1)
            .relationshipsEstablished(1, 0).datasetsDispatched(1).workersReconciled(1, 0);

        AnalysisSynthesisCoordinator.HierarchicalSynthesisResult result =
            coordinator.synthesizeHierarchy(
                new AnalysisSynthesisCoordinator.HierarchicalSynthesisRequest(
                    prompt -> "unused", scope, plan, "analyze returned data",
                    List.of(datasetSummary), List.of(), lifecycle,
                    Map.of("agentRunId", "run-a")));

        assertThat(result.crossDatasetInsights()).isSameAs(bundleResult);
        assertThat(result.hierarchy().finalInputs()).containsExactly(datasetSummary);
        assertThat(result.lifecycle().complete()).isTrue();
        assertThat(result.lifecycle().finalInputCount()).isEqualTo(1);
        verify(adapter).recordRuntimeObservation(any(), any(), any(), any(), any());
    }

    @Test
    void executesFinalModelCallThenAppliesGuardsAndGovernanceExactlyOnce() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        AnalysisSummaryResult governed = AnalysisSummaryResult.finalSummary(
            scope, "completed", "guarded answer", "MODEL_FINAL_SUMMARY", Map.of(), List.of());
        when(governance.finalizeSummary(any())).thenReturn(governed);
        AnswerCandidateCollector candidates = new AnswerCandidateCollector();
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            adapter, "agentRunId", governance, new DeterministicInsightEngine(), candidates,
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("model answer");

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(model, metadata, candidate -> "guarded answer", () -> "fallback", true));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).isEqualTo("guarded answer");
        assertThat(metadata).containsEntry("interpretationPlanSummaryGenerated", true);
        assertThat(candidates.hasCandidates(metadata)).isTrue();
        verify(governance).finalizeSummary(any());
        verify(adapter).recordRuntimeObservation(any(), any(), any(), any(), any());
    }

    @Test
    void degradedTraceableWorkerNarrativeStillReachesTheDriver() {
        AnalysisSummaryGovernanceCoordinator governance = passthroughGovernance();
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult degradedWorkerReport = AnalysisSummaryResult.chunk(
            scope,
            Map.of("datasetReference", "customer_assets", "chunkIndex", 1),
            Map.of(),
            "The returned account snapshot shows 847174.25 total assets, 846262.20 in securities "
                + "and 912.05 cash; the account is therefore almost fully invested.",
            "MODEL_SUMMARY",
            Map.of("structured", false, "rawReplayAvailable", true,
                "analysisNarrativeStatus", "PRESERVED_GOVERNED_WORKER_REPORT"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);
        ChatModel driver = mock(ChatModel.class);
        when(driver.chat(any(String.class))).thenReturn(
            "## Core conclusion\n\nThe returned snapshot is almost fully invested, with only 912.05 cash available.");

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                driver, "prompt", "completed", "run-a", 1, 1, 0,
                true, () -> "fallback", candidate -> candidate, "empty fallback",
                1, 1, true, true, true, 1, 0,
                List.of(degradedWorkerReport), List.of(degradedWorkerReport),
                Map.of("agentRunId", "run-a"), metadata));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("almost fully invested", "912.05");
        assertThat(metadata)
            .containsEntry("analysisDriverModelInvoked", true)
            .containsEntry("finalClaimPublicationContractObserved", false)
            .containsEntry("analysisOutputAdmitted", true)
            .doesNotContainEntry("analysisDriverModelSkipReason", "NO_ADMITTED_SEMANTIC_CLAIMS");
        verify(driver).chat(any(String.class));
    }

    @Test
    void modelFailureUsesGovernedDeterministicFallbackWhenPolicyAllowsIt() {
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        AnalysisSummaryResult governed = AnalysisSummaryResult.finalSummary(
            scope, "completed", "fallback", "DETERMINISTIC_FINAL_FALLBACK", Map.of(), List.of());
        when(governance.finalizeSummary(any())).thenReturn(governed);
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel failing = mock(ChatModel.class);
        when(failing.chat(any(String.class))).thenThrow(new IllegalStateException("model unavailable"));

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(failing, metadata, candidate -> candidate, () -> "fallback", true));

        assertThat(result.content()).isEqualTo("fallback");
        assertThat(metadata)
            .containsEntry("interpretationPlanDeterministicSummaryFallback", true)
            .containsEntry("executionStatus", "PARTIAL_RESULT_PRESENTED")
            .containsEntry("interpretationPlanSummaryGenerated", false)
            .containsEntry("interpretationPlanFinalResultProduced", true);
    }

    @Test
    void modelFailureNeverPublishesRawRuntimeEnvelopeAsAnalysis() {
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        when(governance.finalizeSummary(any())).thenAnswer(invocation -> {
            AnalysisSummaryGovernanceCoordinator.FinalSummaryRequest request = invocation.getArgument(0);
            return AnalysisSummaryResult.finalSummary(
                scope, "completed", request.content(), request.outcome(), Map.of(), List.of());
        });
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel failing = mock(ChatModel.class);
        when(failing.chat(any(String.class))).thenThrow(new IllegalStateException("model unavailable"));
        String rawFallback = "## 可用执行结果\n\n- 返回内容：`{\"_aggregation\":\"STRUCTURED_MAP\","
            + "\"_fieldCount\":8,\"_assessmentCapability\":\"LIMITED\","
            + "\"schemaVersion\":\"tool_execution_result.v1\",\"toolName\":\"query\","
            + "\"runtimeMetadata\":{},\"dataCompleteness\":{},\"sourceMetadata\":{},"
            + "\"payloadType\":\"structured\",\"padding\":\"" + "x".repeat(1_000)
            + "\"}`\n成功子项：1；未成功子项：0";

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(failing, metadata, candidate -> candidate, () -> rawFallback, true));

        assertThat(result.content())
            .contains("分析未完成", "支撑数据", "复用已获取的数据")
            .doesNotContain("_aggregation", "可用执行结果", "toolName");
        assertThat(result.generated()).isFalse();
        assertThat(metadata)
            .containsEntry("analysisOutputAdmitted", false)
            .containsEntry("analysisOutputAdmissionReason", "EXECUTION_MANIFEST_NOT_ANALYSIS")
            .containsEntry("rawAnalysisOutputWithheld", true)
            .containsEntry("executionStatus", "NO_PRESENTABLE_ANALYSIS")
            .containsEntry("interpretationPlanFinalResultProduced", false);
    }

    @Test
    void nonEmptyModelResponseStillCannotPublishRawRuntimeEnvelope() {
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        when(governance.finalizeSummary(any())).thenAnswer(invocation -> {
            AnalysisSummaryGovernanceCoordinator.FinalSummaryRequest request = invocation.getArgument(0);
            return AnalysisSummaryResult.finalSummary(
                scope, "completed", request.content(), request.outcome(), Map.of(), List.of());
        });
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(
            "## 可用执行结果\n- 返回内容：`{\"_aggregation\":\"STRUCTURED_MAP\","
                + "\"_fieldCount\":8,\"_assessmentCapability\":\"LIMITED\","
                + "\"schemaVersion\":\"result.v1\",\"toolName\":\"query\","
                + "\"runtimeMetadata\":{},\"dataCompleteness\":{},\"sourceMetadata\":{},"
                + "\"payloadType\":\"structured\",\"padding\":\"" + "x".repeat(1_000)
                + "\"}`\n成功子项：1；未成功子项：0");

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(model, metadata, candidate -> candidate, () -> "unused", true));

        assertThat(result.content()).contains("分析未完成", "复用已获取的数据");
        assertThat(result.generated()).isFalse();
        assertThat(metadata)
            .containsEntry("analysisOutputAdmitted", false)
            .containsEntry("rawAnalysisOutputWithheld", true)
            .containsEntry("executionStatus", "NO_PRESENTABLE_ANALYSIS");
    }

    @Test
    void presentationFailsClosedWhenReturnedDataHasNoGovernedWorkerAnalysis() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            mock(AnalysisSummaryGovernanceCoordinator.class),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        Map<String, Object> metadata = new LinkedHashMap<>();

        String answer = coordinator.presentGovernedAnalysis(
            "## 可用执行结果\n\n- 返回内容：`raw rows`\n成功子项：1；未成功子项：0",
            new AnalysisSynthesisCoordinator.PresentationRequest(
                "raw appendix", List.of(List.of("raw")), 1, false,
                true, true, true, List.of(), List.of(), metadata));

        assertThat(answer).isEqualTo(AnalysisOutputAdmissionPolicy.WITHHELD_MESSAGE);
        assertThat(metadata)
            .containsEntry("governedNarrativeAnalysisUnavailable", true)
            .containsEntry("ungovernedCandidateWithheld", true)
            .containsEntry("analysisOutputAdmitted", false);
    }

    @Test
    void deadlineCancellationIsNotConvertedIntoAContentFallback() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            mock(AnalysisSummaryGovernanceCoordinator.class),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel timedOut = mock(ChatModel.class);
        when(timedOut.chat(any(String.class)))
            .thenThrow(new AgentDeadlineExceededException("deadline exhausted"));

        assertThatThrownBy(() -> coordinator.synthesizeFinal(
            request(timedOut, new LinkedHashMap<>(), candidate -> candidate,
                () -> "must not be used", true)))
            .isInstanceOf(AgentDeadlineExceededException.class)
            .hasMessageContaining("deadline exhausted");
    }

    @Test
    void governanceBarrierBecomesReviewAdvisoryAndDoesNotSkipDriver() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(
            "## 分析结论\n\n当前已有分析产品需要人工复核，但不应被机器治理阻断。");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", false);
        metadata.put("analysisSynthesisBarrierStatus", "BLOCKED");
        metadata.put("analysisAcceptedWorkerCount", 0);
        metadata.put("analysisRejectedWorkerCount", 2);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            request(model, metadata, candidate -> candidate, () -> "unused", true));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("需要人工复核", "不应被机器治理阻断")
            .doesNotContain("分析未完成");
        assertThat(metadata)
            .containsEntry("analysisSynthesisBarrierWasAdvisory", true)
            .containsEntry("analysisSynthesisBarrierReady", true)
            .containsEntry("analysisHumanReviewRequired", true)
            .containsEntry("analysisRepairRequired", false)
            .containsEntry("analysisDriverModelInvoked", true)
            .containsEntry("analysisExecutionStatus", "COMPLETED");
        verify(model).chat(any(String.class));
    }

    @Test
    void missingDriverReviewProtocolPublishesDeterministicEvidenceForHumanReview() {
        AnalysisSummaryGovernanceCoordinator governance = passthroughGovernance();
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", governance,
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("客户具有模型擅自推断出的稳定交易风格");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            claimBoundRequest(model, metadata, claimSummary(), true));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("返回记录显示数值为 42")
            .doesNotContain("分析未完成", "稳定交易风格");
        assertThat(metadata)
            .containsEntry("finalClaimPublicationContractActive", true)
            .containsEntry("analysisDriverReviewCompleted", false)
            .containsEntry("analysisDriverReviewStatus", "INVALID")
            .containsEntry("analysisHumanReviewRequired", true)
            .containsEntry("analysisRepairRequired", false)
            .containsEntry("analysisDriverDecisionAction", "PUBLISH_WITH_REVIEW_NOTES")
            .containsEntry("analysisExecutionStatus", "COMPLETED");
        assertThat(metadata).doesNotContainKeys(
            "analysisRetryDirective", "analysisReuseExistingDataset");
    }

    @Test
    void finalModelFailurePublishesDeterministicEvidenceInsteadOfBlockingForDriverReview() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenThrow(new IllegalStateException("model unavailable"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            claimBoundRequest(model, metadata, claimSummary(), false));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("返回记录显示数值为 42")
            .doesNotContain("分析未完成");
        assertThat(metadata)
            .containsEntry("interpretationPlanDeterministicClaimFallback", true)
            .containsEntry("analysisDriverReviewCompleted", false)
            .containsEntry("analysisHumanReviewRequired", true)
            .containsEntry("analysisRepairRequired", false)
            .containsEntry("analysisExecutionStatus", "COMPLETED");
        assertThat(metadata).doesNotContainKeys(
            "analysisRetryDirective", "analysisReuseExistingDataset");
    }

    @Test
    void driverReceivesDemandAndMetricAssociationTaskAndPublishesSafeDirections() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
            prompt.contains("demandAnalysis")
                && prompt.contains("metricAssociations")
                && prompt.contains("managementReview")
                && prompt.contains("manager reviewing completed Worker analysis reports")
                && prompt.contains("Admitted claim ledger"))))
            .thenReturn("""
                {"schemaVersion":"governed_management_synthesis.v3",
                 "driverReview":{"status":"PASS","requirementCoverage":[],"claimConsistency":[],
                   "evidenceSufficiency":{},"crossWorkerConflicts":[],"duplicateEvidence":[],
                   "unsupportedInferences":[],"missingCriticalDimensions":[],
                   "claimAssessments":[{"claimId":"claim-1","verdict":"ACCEPT","reason":"grounded"}],
                   "challenges":[]},
                 "driverReasoning":{"derivedClaims":[]},
                 "findings":[{"section":"CORE","text":"返回记录显示数值为 42","basisClaimIds":["claim-1"]}],
                 "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"answers objective"}],
                 "demandAnalysis":{"decisionGoal":"定位增长来源与风险",
                   "priorityQuestions":["收益是否集中"]},
                 "metricAssociations":[{"title":"检验收益贡献与集中度",
                   "basisClaimIds":["claim-1"],"candidateMetrics":["收益贡献率","持仓占比"],
                   "analysisMethod":"按标的对照","validationNeeded":["完整持仓"]}],
                 "managementReview":{
                   "overallAssessment":{"text":"Worker已形成初步事实判断，但解释深度不足",
                     "basisClaimIds":["claim-1"]},
                   "identifiedProblems":[{"text":"缺少比较基准","basisClaimIds":["claim-1"]}],
                   "improvementSuggestions":[{"text":"补充历史同口径数据","basisClaimIds":["claim-1"]}],
                   "nextWorkDirections":[{"text":"优先完成趋势验证","basisClaimIds":["claim-1"]}]}}
                """);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            claimBoundRequest(model, metadata, claimSummary(), true));

        assertThat(result.content()).contains(
            "## 需求理解与未决问题", "定位增长来源与风险",
            "## 指标联想与待验证方向", "收益贡献率",
            "## 管理复盘与下一步", "缺少比较基准", "优先完成趋势验证");
        assertThat(metadata)
            .containsEntry("analysisDriverModelInvoked", true)
            .containsEntry("finalClaimSelectionAccepted", true);
    }

    @Test
    void driverUsesReducerReportsInsteadOfBypassingThemWithChunkClaims() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult chunk = claimSummary();
        Map<String, Object> reducerInsight = Map.of(
            "claimId", "claim-reducer",
            "claim", "Reducer归并后的管理分析结论",
            "claimClass", "OBSERVED_RETURNED_FACT",
            "confidence", "HIGH",
            "recordRefs", List.of("dataset.records[1]"),
            "supportingValues", List.of("42"),
            "caveats", List.of());
        AnalysisSummaryResult reducer = AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "dataset-summary#dataset-a",
            "Reducer归并后的管理分析结论", "MODEL_DATASET_REDUCE",
            Map.of("datasetReference", "dataset-a"), Map.of(), Map.of("complete", true),
            List.of(chunk), Map.of(
                "insights", List.of(reducerInsight),
                "claimAdmissionDecisions", List.of(Map.of(
                    "claimId", "claim-reducer", "admitted", true))));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
            prompt.contains("claim-reducer") && !prompt.contains("claim-1"))))
            .thenReturn("""
                {"schemaVersion":"governed_management_synthesis.v3",
                 "driverReview":{"status":"PASS","requirementCoverage":[],"claimConsistency":[],
                   "evidenceSufficiency":{},"crossWorkerConflicts":[],"duplicateEvidence":[],
                   "unsupportedInferences":[],"missingCriticalDimensions":[],
                   "claimAssessments":[{"claimId":"claim-reducer","verdict":"ACCEPT","reason":"grounded"}],
                   "challenges":[]},
                 "driverReasoning":{"derivedClaims":[]},
                 "findings":[{"section":"CORE","text":"Reducer归并后的管理分析结论","basisClaimIds":["claim-reducer"]}],
                 "coverage":[{"claimId":"claim-reducer","disposition":"USED","reason":"management finding"}]}
                """);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);
        AnalysisSynthesisCoordinator.FinalModelSynthesisRequest request =
            new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                model, "prompt", "completed", "run-a", 2, 1, 3,
                true, () -> "unsafe fallback", candidate -> candidate, "empty fallback",
                1, 1, true, true, true, 1, 0,
                List.of(chunk), List.of(reducer), Map.of("agentRunId", "run-a"), metadata);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(request);

        assertThat(result.content()).contains("Reducer归并后的管理分析结论")
            .doesNotContain("返回记录显示数值为 42");
        assertThat(metadata)
            .containsEntry("analysisDecisionOperatingModelVersion",
                "data_analysis_decision_operating_model.v1")
            .containsEntry("analysisParticipantRole", "DRIVER")
            .containsEntry("analysisDriverInputMode", "GOVERNED_WORKER_REDUCER_REPORTS_ONLY")
            .containsEntry("analysisDriverInputReportCount", 1)
            .containsKeys("analysisDriverAdmission", "analysisEvidenceLineage",
                "analysisClaimLifecycle");
        assertThat(result.governedResult().evidence())
            .containsKeys("analysisReportAdmission", "analysisEvidenceLineage",
                "analysisClaimLifecycle", "analysisPublishedClaimIds");
        assertThat(result.governedResult().evidence().get("analysisClaimLifecycle").toString())
            .contains("claim-reducer", "SYNTHESIZED", "PUBLISHED");
        DataAnalysisLineageGraph graph = DataAnalysisLineageGraph.fromMap(
            metadata.get("analysisLineageGraph"));
        assertThat(graph.ancestors(result.governedResult().resultId()))
            .extracting(DataAnalysisLineageGraph.Node::nodeId)
            .contains(reducer.resultId(), "claim-reducer");
    }

    @Test
    void driverStillProducesHumanReviewableAnalysisWhenClaimsAreNotAdmitted() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
            prompt.contains("Binding Driver role pipeline context"))))
            .thenReturn("## 管理分析\n\n下层报告存在未通过证据绑定的推断，现有内容可供人工复核，但不应视为已验证事实。");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);
        AnalysisSummaryResult rejected = claimSummary().withEvidence(Map.of(
            "insights", List.of(Map.of(
                "claimId", "claim-1", "claim", "不得发布的推断",
                "claimClass", "INFERRED", "recordRefs", List.of("dataset.records[1]"),
                "supportingValues", List.of("42"))),
            "claimAdmissionDecisions", List.of(Map.of(
                "claimId", "claim-1", "admitted", false))));

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            claimBoundRequest(model, metadata, rejected, true));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("管理分析", "人工复核");
        assertThat(metadata)
            .containsEntry("finalClaimPublicationContractObserved", true)
            .containsEntry("finalAdmittedClaimCount", 0)
            .containsEntry("analysisDriverModelInvoked", true)
            .containsEntry("finalPayloadType", "DRIVER_REPORT")
            .containsKey("analysisReportContract");
        assertThat((Map<String, Object>) metadata.get("analysisReportContract"))
            .containsEntry("reportType", "DRIVER_REPORT")
            .containsEntry("publishability", "PUBLISHABLE_REPORT");
    }

    @Test
    void driverPublishesGroundedManagementSynthesisInsteadOfClaimInventory() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult assets = factReport("assets", "fact:assets",
            "Worker: assets are concentrated in securities and cash is limited.",
            "Total assets 847174.25, security value 846262.20, cash 912.05",
            List.of("847174.25", "846262.20", "912.05"));
        AnalysisSummaryResult trades = factReport("trades", "fact:trades",
            "Worker: the returned day contains active two-way trading.",
            "There are 20 trades: 11 buys and 9 sells", List.of("20", "11", "9"));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
            prompt.contains("Worker: assets are concentrated")
                && prompt.contains("fact:assets") && prompt.contains("coverage")
                && prompt.contains("ADVISORY_ONLY")
                && prompt.contains("never treat their count as a publication veto"))))
            .thenReturn("""
                {"schemaVersion":"governed_management_synthesis.v3",
                 "driverReview":{"status":"PASS","requirementCoverage":[],"claimConsistency":[],
                   "evidenceSufficiency":{},"crossWorkerConflicts":[],"duplicateEvidence":[],
                   "unsupportedInferences":[],"missingCriticalDimensions":[],
                   "claimAssessments":[
                     {"claimId":"fact:assets","verdict":"ACCEPT","reason":"grounded"},
                     {"claimId":"fact:trades","verdict":"ACCEPT","reason":"grounded"}],
                   "challenges":[]},
                 "driverReasoning":{"derivedClaims":[{"derivedClaimId":"driver-derived:portfolio-activity",
                   "text":"The account combines limited cash flexibility with active two-way trading.",
                   "basisClaimIds":["fact:assets","fact:trades"],"caveats":[]}]},
                 "findings":[
                   {"section":"CORE","text":"The account is almost entirely invested in securities with little cash flexibility, while the returned day also shows active two-way trading.",
                    "basisClaimIds":["fact:assets","fact:trades"]},
                   {"section":"EVIDENCE","text":"Total assets are 847174.25, security value is 846262.20 and cash is 912.05; the day contains 20 trades, including 11 buys and 9 sells.",
                    "basisClaimIds":["fact:assets","fact:trades"]}],
                 "coverage":[
                   {"claimId":"fact:assets","disposition":"USED","reason":"asset structure"},
                   {"claimId":"fact:trades","disposition":"USED","reason":"trading activity"}]}
                """);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);
        metadata.put("analysisGapRequests", java.util.stream.IntStream.range(0, 36)
            .mapToObj(index -> Map.<String, Object>of(
                "questionId", "gap-" + index,
                "goal", "optional follow-up " + index))
            .toList());
        AnalysisSynthesisCoordinator.FinalModelSynthesisRequest request =
            new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                model, "Completed reports:\n" + assets.content() + "\n" + trades.content(),
                "completed", "run-a", 2, 2, 4, true, () -> "unsafe fallback",
                candidate -> candidate, "empty fallback", 2, 2,
                true, true, true, 2, 0, List.of(assets, trades), List.of(assets, trades),
                Map.of("agentRunId", "run-a"), metadata);

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(request);

        assertThat(result.content()).contains(
            "almost entirely invested in securities", "847174.25", "11 buys and 9 sells");
        assertThat(metadata)
            .containsEntry("finalClaimSelectionAccepted", true)
            .containsEntry("finalClaimSelectionReason", "GROUNDED_MANAGEMENT_SYNTHESIS_ADMITTED");
        assertThat(metadata.get("finalPublishedClaimIds").toString())
            .contains("driver-derived:portfolio-activity");
        assertThat(result.governedResult().evidence().get("analysisArtifacts").toString())
            .contains("analysis_artifact.v1", "fact:assets", "fact:trades",
                "driver-derived:portfolio-activity", "basisClaimIds=[fact:assets, fact:trades]");
        Map<?, ?> driverContext = (Map<?, ?>) metadata.get("analysisDriverPipelineContext");
        assertThat(driverContext.get("evidenceGapCount")).isEqualTo(36);
        assertThat((List<?>) driverContext.get("evidenceGaps")).hasSize(8);
    }

    @Test
    void presentationReplacesUngovernedDraftWithDriverSynthesis() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId",
            mock(AnalysisSummaryGovernanceCoordinator.class),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult summary = datasetSummary("dataset-a", "完整的业务分析结论");
        Map<String, Object> metadata = new LinkedHashMap<>();

        String answer = coordinator.presentGovernedAnalysis("operational draft",
            new AnalysisSynthesisCoordinator.PresentationRequest(
                "raw appendix", List.of(List.of("完整")), 1, false,
                true, true, true, List.of(summary), List.of(summary), metadata));

        assertThat(answer).contains("数据分析总结", "完整的业务分析结论")
            .doesNotContain("operational draft", "raw appendix");
        assertThat(metadata)
            .containsEntry("governedNarrativeAnalysisReplacedOperationalDraft", true)
            .containsEntry("governedNarrativeAnalysisSource", "DRIVER_SYNTHESIS_INPUTS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void driverChallengeBecomesHumanReviewNoteWithoutBlockingPublication() {
        AnalysisSynthesisCoordinator coordinator = new AnalysisSynthesisCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", passthroughGovernance(),
            new DeterministicInsightEngine(), new AnswerCandidateCollector(),
            new HierarchicalAnalysisReducer());
        AnalysisSummaryResult report = claimSummary();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
            prompt.contains("DRIVER_REVIEW")
                && prompt.contains("CHIEF_DECISION_MAKER")
                && prompt.contains("客户经营分析决策者")
                && prompt.contains("claim-1"))))
            .thenReturn("""
                {"schemaVersion":"governed_management_synthesis.v3",
                 "driverReview":{"status":"CHALLENGE","requirementCoverage":[],
                   "claimConsistency":[],"evidenceSufficiency":{},"crossWorkerConflicts":[],
                   "duplicateEvidence":[],"unsupportedInferences":["missing comparison basis"],
                   "missingCriticalDimensions":["historical baseline"],
                   "claimAssessments":[{"claimId":"claim-1","verdict":"DOWNGRADE",
                     "reason":"No comparison basis"}],
                   "challenges":[{"targetLayer":"WORKER_REPORT",
                     "targetReportId":"tenant-a:run-a:dataset-a#chunk-1",
                     "claimIds":["claim-1"],"reason":"No comparison basis",
                     "requiredCorrection":"Reanalyze as a current observation only"}]},
                 "driverReasoning":{"derivedClaims":[]},
                 "findings":[{"section":"CORE","text":"The returned observation is 42, but no comparison basis supports a trend judgment.",
                   "basisClaimIds":["claim-1"]}],
                 "coverage":[{"claimId":"claim-1","disposition":"USED","reason":"current observation"}]}
                """);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisSynthesisBarrierReady", true);
        Map<String, Object> runtime = Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE,
            AgentRoleAnalysisContext.create("客户经营分析决策者",
                "审查客户经营分析并形成管理决策", List.of("客户分析"), List.of("决策")));

        AnalysisSynthesisCoordinator.FinalSynthesisResult result = coordinator.synthesizeFinal(
            new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
                model, "prompt", "completed", "run-a", 2, 1, 3,
                true, () -> "unsafe", candidate -> candidate, "empty",
                1, 1, true, true, true, 1, 0,
                List.of(report), List.of(report), runtime, metadata));

        assertThat(result.generated()).isTrue();
        assertThat(result.content()).contains("returned observation is 42", "no comparison basis");
        assertThat(metadata)
            .containsEntry("analysisDriverReviewStatus", "CHALLENGE")
            .containsEntry("analysisDriverDecisionAction", "APPROVE_WITH_REVIEW_NOTES")
            .containsEntry("analysisRepairRequired", false)
            .containsEntry("analysisHumanReviewRequired", true)
            .containsEntry("analysisSynthesisBarrierReady", true)
            .containsKey("analysisDriverPipelineContext")
            .containsKey("analysisDriverSuggestedRepairRequests");
        assertThat(metadata).extracting("analysisDriverDecisionStages").asString()
            .contains("READY_WITH_REVIEW_NOTES");
        assertThat(metadata).containsEntry("analysisExecutionStatus", "COMPLETED");
    }

    private AnalysisSynthesisCoordinator.FinalModelSynthesisRequest request(
        ChatModel model,
        Map<String, Object> metadata,
        java.util.function.UnaryOperator<String> guard,
        java.util.function.Supplier<String> fallback,
        boolean fallbackAllowed
    ) {
        return new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
            model, "prompt", "completed", "run-a", 2, 1, 3,
            fallbackAllowed, fallback, guard, "empty fallback",
            1, 1, true, true, true, 1, 0,
            List.of(), List.of(), Map.of("agentRunId", "run-a"), metadata);
    }

    private AnalysisSummaryResult datasetSummary(String dataset, String content) {
        return AnalysisSummaryResult.intermediateSummary(
            scope, "DATASET_SYNTHESIS", "dataset-summary#" + dataset, content,
            "MODEL_DATASET_REDUCE", Map.of("datasetReference", dataset), Map.of(),
            Map.of("complete", true), List.of(), Map.of());
    }

    private AnalysisSynthesisCoordinator.FinalModelSynthesisRequest claimBoundRequest(
        ChatModel model, Map<String, Object> metadata, AnalysisSummaryResult summary,
        boolean fallbackAllowed
    ) {
        return new AnalysisSynthesisCoordinator.FinalModelSynthesisRequest(
            model, "prompt", "completed", "run-a", 2, 1, 3,
            fallbackAllowed, () -> "unsafe raw fallback", candidate -> candidate, "empty fallback",
            1, 1, true, true, true, 1, 0,
            List.of(summary), List.of(summary), Map.of("agentRunId", "run-a"), metadata);
    }

    private AnalysisSummaryResult claimSummary() {
        Map<String, Object> insight = Map.of(
            "claimId", "claim-1",
            "claim", "返回记录显示数值为 42",
            "claimClass", "OBSERVED_RETURNED_FACT",
            "confidence", "HIGH",
            "recordRefs", List.of("dataset.records[1]"),
            "supportingValues", List.of("42"),
            "caveats", List.of());
        return AnalysisSummaryResult.chunk(scope,
            Map.of("datasetReference", "dataset-a", "chunkIndex", 1), Map.of(),
            "返回记录显示数值为 42", "MODEL_SUMMARY", Map.of(
                "insights", List.of(insight),
                "claimAdmissionDecisions", List.of(Map.of(
                    "claimId", "claim-1", "admitted", true))));
    }

    private AnalysisSummaryResult factReport(String dataset, String claimId, String report,
                                             String claim, List<String> values) {
        return AnalysisSummaryResult.chunk(scope,
            Map.of("datasetReference", dataset, "chunkIndex", 1), Map.of(),
            report, "MODEL_SUMMARY", Map.of("observedFactClaims", List.of(Map.of(
                "claimId", claimId, "claim", claim,
                "recordRefs", List.of(dataset + ".records[1]"),
                "supportingValues", values, "confidence", "HIGH", "caveats", List.of()))));
    }

    private AnalysisSummaryGovernanceCoordinator passthroughGovernance() {
        AnalysisSummaryGovernanceCoordinator governance = mock(AnalysisSummaryGovernanceCoordinator.class);
        when(governance.finalizeSummary(any())).thenAnswer(invocation -> {
            AnalysisSummaryGovernanceCoordinator.FinalSummaryRequest request = invocation.getArgument(0);
            return AnalysisSummaryResult.finalSummary(
                scope, "completed", request.content(), request.outcome(), Map.of(), List.of());
        });
        return governance;
    }
}
