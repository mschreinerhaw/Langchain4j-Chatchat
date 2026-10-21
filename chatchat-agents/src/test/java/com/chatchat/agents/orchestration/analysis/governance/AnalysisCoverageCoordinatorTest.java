package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.nodes.synthesis.FinalSynthesisNode;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.orchestration.analysis.graph.AnalysisCodingPort;
import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisCoverageCoordinatorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OPTIONAL,true,false,true", "REQUIRED,true,false,false", "OPTIONAL,false,false,false", "REQUIRED,false,false,false",
        "REQUIRED,true,true,true", "OPTIONAL,true,true,true"})
    void businessSourceDependencyControlsFileAnalysis(String dependency, boolean success, boolean hasData, boolean continues) {
        var evidence=mock(AnalysisEvidenceCoordinator.class);
        var businessRows=hasData?List.<Map<String,Object>>of(Map.of("id",1)):List.<Map<String,Object>>of();
        when(evidence.project(any(),any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(hasData?
            List.of(new AnalysisEvidenceCoordinator.Dataset("mcp-data",Map.of(),businessRows)):List.of(),List.of()));
        var coordinator=coordinator(evidence);var coding=mock(AnalysisCodingPort.class);coordinator.setCodingPort(coding);
        var file=new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileInput("one.xlsx","user-asset:"+"a".repeat(64)+":"+"b".repeat(64),"application/xlsx",42,"b".repeat(64));
        var plan=new com.chatchat.common.runtime.capability.ProblemAnalysisPlan(com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Status.READY,
            "analyze", "files", "data", "file evidence", List.of(new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Task("analyze",
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.Intent.DATA_ANALYSIS,List.of("original"),"report")),"",
            new com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileAnalysis(List.of(file),
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.ToolDataDependency.valueOf(dependency)));
        var step=new InterpretationPlanRuntime.StepExecution(1,"tool_call","opaque",success,Map.of("rows",businessRows),
            success?null:"read failed",null,null,1);
        var result=new InterpretationPlanRuntime.ExecutionResult(success?"completed":"failed",success,false,null,null,List.of(step),Map.of(),1);
        var metadata=new LinkedHashMap<String,Object>();var base=request(metadata);
        var request=new AnalysisCoverageCoordinator.Request(null,"compare originals",result,Map.of("taskType","DATA_ANALYSIS","problemAnalysisPlan",plan),
            metadata,()->false,()->{},base.isolationScope(),base.summaryProtocol());
        if (!continues) {
            assertThatThrownBy(()->coordinator.analyze(request)).hasMessageContaining("REQUIRED_MCP_DATA_UNAVAILABLE");
            org.mockito.Mockito.verifyNoInteractions(coding);
        } else {
            when(coding.prepareFileSources(any())).thenReturn(Map.of(file.reference(),new AnalysisEvidenceCoordinator.Dataset(file.reference(),Map.of(),List.of(Map.of("fileName","one.xlsx")))));
            when(coding.executePrimaryAnalysis(any(),any(),any())).thenAnswer(call->{
                Map<String,AnalysisEvidenceCoordinator.Dataset> sources=call.getArgument(1);
                assertThat(sources).containsKey(file.reference());
                if (hasData) assertThat(sources).containsKey("mcp-data");
                sources.put("result",new AnalysisEvidenceCoordinator.Dataset("result",Map.of(),List.of(Map.of("reportMarkdown","computed quality report",
                    "evidenceReferences",List.of(file.reference()),"limitations",List.of("Optional MCP returned no rows")))));
                return Map.of("resultAvailable",true,"datasetReference","result");
            });
            coordinator.analyze(request);
            assertThat(metadata).containsEntry("optionalToolDataEmpty",!hasData).containsEntry("modelNativeReportDraft","computed quality report");
            org.mockito.Mockito.verify(coding,org.mockito.Mockito.times(1)).executePrimaryAnalysis(any(),any(),any());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DATA_ANALYSIS","DOCUMENT","DIRECT_ANSWER","ASSET_GUIDANCE"})
    void dataAnalysisDelegatesTheWholeAnalysisToCodingWithoutCallingTheJavaModel(String family) {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        var datasets = List.of(
            new AnalysisEvidenceCoordinator.Dataset("source-1", Map.of(), List.of(Map.of("key", "A", "value", 1))),
            new AnalysisEvidenceCoordinator.Dataset("source-2", Map.of(), List.of(Map.of("key", "A", "value", 2))));
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(datasets, List.of()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var coding = new AnalysisCodingPort() {
            public Map<String,String> capabilities() { return Map.of(); }
            public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context, Map<String,Object> request,
                    Map<String,AnalysisEvidenceCoordinator.Dataset> sources) { throw new AssertionError("legacy coding loop used"); }
            public Map<String,Object> executePrimaryAnalysis(AnalysisCoverageCoordinator.Request context,
                    Map<String,AnalysisEvidenceCoordinator.Dataset> sources,
                    java.util.function.Consumer<Map<String,Object>> observer) {
                calls.incrementAndGet();
                sources.put("coding-result", new AnalysisEvidenceCoordinator.Dataset("coding-result", Map.of(), List.of(Map.of(
                    "reportMarkdown", "# Result\nComputed by Coding.",
                    "evidenceReferences", List.of("source-1", "source-2"),
                    "limitations", List.of("None declared")))));
                return Map.of("status", "COMPLETED", "resultAvailable", true,
                    "datasetReference", "coding-result", "authorizedDatasetReferences", List.of("source-1", "source-2"),
                    "evidenceRefs", List.of("runtime-evidence:receipt"));
            }
        };
        var coordinator = coordinator(evidence);
        coordinator.setCodingPort(coding);
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        var metadata = new LinkedHashMap<String,Object>();
        var base = request(metadata);

        var coverage = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", base.result(),
            Map.of("workflowFamily", family, "modelAnalysisProtocol", "model_native_analysis.v2"), metadata,
            () -> false, () -> {}, base.isolationScope(), base.summaryProtocol()));

        assertThat(calls).hasValue(1);
        assertThat(metadata).containsEntry("datasetAnalysisMode", "CODING_OWNED_DATA_ANALYSIS")
            .containsEntry("modelNativeReportDraft", "# Result\nComputed by Coding.")
            .containsEntry("javaDataExplorationSkipped", true)
            .doesNotContainKey("modelAnalysisProtocol");
        assertThat(coverage.returnedRecordCount()).isEqualTo(2);
        assertThat(coverage.iterations()).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"workflowFamily","taskType"})
    void modelClassifiedDataAnalysisRequiresPythonBeforeAnyRawDataPrompt(String classificationKey) {
        var evidence=mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(),any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(
            new AnalysisEvidenceCoordinator.Dataset("source",Map.of(),List.of(Map.of("value",1)))),List.of()));
        var model=mock(dev.langchain4j.model.chat.ChatModel.class);
        var metadata=new LinkedHashMap<String,Object>();var base=request(metadata);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->coordinator(evidence).analyze(
            new AnalysisCoverageCoordinator.Request(model,"Question",base.result(),Map.of(classificationKey,"DATA_ANALYSIS"),
                metadata,()->false,()->{},base.isolationScope(),base.summaryProtocol())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PYTHON_ANALYSIS_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test void dataAnalysisRejectsEvidenceReferencesOutsideTheAuthorizedSnapshot() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(
            new AnalysisEvidenceCoordinator.Dataset("authorized-source", Map.of(), List.of(Map.of("value", 1)))), List.of()));
        var coding = new AnalysisCodingPort() {
            public Map<String,String> capabilities() { return Map.of(); }
            public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context, Map<String,Object> request,
                    Map<String,AnalysisEvidenceCoordinator.Dataset> sources) { throw new AssertionError("legacy coding loop used"); }
            public Map<String,Object> executePrimaryAnalysis(AnalysisCoverageCoordinator.Request context,
                    Map<String,AnalysisEvidenceCoordinator.Dataset> sources,
                    java.util.function.Consumer<Map<String,Object>> observer) {
                sources.put("coding-result", new AnalysisEvidenceCoordinator.Dataset("coding-result", Map.of(), List.of(Map.of(
                    "reportMarkdown", "Unsupported conclusion",
                    "evidenceReferences", List.of("unauthorized-source"),
                    "limitations", List.of()))));
                return Map.of("status", "COMPLETED", "resultAvailable", true,
                    "datasetReference", "coding-result", "authorizedDatasetReferences", List.of("authorized-source"));
            }
        };
        var coordinator = coordinator(evidence);
        coordinator.setCodingPort(coding);
        var metadata = new LinkedHashMap<String,Object>();
        var base = request(metadata);

        assertThatThrownBy(() -> coordinator.analyze(new AnalysisCoverageCoordinator.Request(null, "Question", base.result(),
            Map.of("workflowFamily", "DATA_ANALYSIS"), metadata, () -> false, () -> {},
            base.isolationScope(), base.summaryProtocol())))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("outside its authorized immutable inputs");
    }
    @Test void explicitV2ModeCanThinkWithoutInitialDatasetsAndRetainItsCompletionIntent() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(
                com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of(
                    "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "COMPLETE"),
                    "output_type", "final", "content", "Working notes", "plan", validPlan(), "evidenceRequests", List.of())))).build());
        var coordinator = coordinator(evidence); var metadata = new LinkedHashMap<String,Object>();
        var base = request(metadata);
        var result = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", base.result(),
            Map.of("modelAnalysisProtocol", "model_native_analysis.v2"), metadata, () -> false, () -> {}, base.isolationScope(), base.summaryProtocol()));
        assertThat(metadata).containsEntry("publicationState", "REQUESTED").containsEntry("modelAnalysisOutput", "Working notes");
        assertThat(((Map<?,?>) metadata.get("modelDecision")).get("action")).isEqualTo("COMPLETE");
        assertThat(result.returnedRecordCount()).isZero();
        org.mockito.Mockito.verify(model).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeHarnessOwnsAnalysisWhileSourceFailuresRemainObservable() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        var datasets = java.util.stream.IntStream.range(0, 3).mapToObj(index ->
            new AnalysisEvidenceCoordinator.Dataset("source-" + index, Map.of(),
                List.of(Map.<String,Object>of("value", index, "sourceComplete", false)))).toList();
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(datasets,
            List.of(Map.of("datasetReference", "failed-source", "accountingStatus", "FAILED", "reason", "SOURCE_EXECUTION_FAILED"))));
        var insight = mock(DeterministicInsightEngine.class);
        var protocol = mock(DataAnalysisSummaryProtocol.class);
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(
                com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of(
                    "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "COMPLETE"),
                    "output_type", "final", "content", "Model-selected final analysis.", "plan", validPlan(), "evidenceRequests", List.of())))).build());
        var coordinator = new AnalysisCoverageCoordinator(mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
        var metadata = new LinkedHashMap<String,Object>();
        var execution = new InterpretationPlanRuntime.ExecutionResult("completed", true, false, null, null, List.of(), Map.of(), 1);
        var result = coordinator.analyze(new AnalysisCoverageCoordinator.Request(model, "Question", execution, Map.of(), metadata,
            () -> false, () -> {}, GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"), protocol));
        assertThat(metadata).containsEntry("modelNativeHarnessActive", true)
            .containsEntry("modelAnalysisOutput", "Model-selected final analysis.")
            .containsEntry("recordAnalysisFailedDatasetCount", 1);
        assertThat(result.sourceContentComplete()).isFalse();
        assertThat(result.evidenceTraceComplete()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(insight, protocol);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsCompleteEmptyCoverageWithoutDispatchWhenNoEvidenceDatasetsExist() {
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        AnalysisCoverageCoordinator coordinator = new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of(), 1);

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            new AnalysisCoverageCoordinator.Request(null, "question", result, Map.of(),
                new java.util.LinkedHashMap<>(), () -> false, () -> {},
                GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
                mock(DataAnalysisSummaryProtocol.class)));

        assertThat(coverage.returnedRecordCount()).isZero();
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.evidenceTraceComplete()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptySourceIsPresentInCompletionAccounting() {
        Map<String, Object> exclusion = Map.of(
            "datasetReference", "empty-source",
            "accountingStatus", "EXCLUDED",
            "reason", "NO_PROJECTED_CONTENT");
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of(exclusion)));
        AnalysisCoverageCoordinator coordinator = coordinator(evidence);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            request(metadata));

        assertThat(coverage.coverageComplete()).isTrue();
        assertThat((Map<String, Object>) metadata.get("datasetCompletionSnapshot"))
            .containsEntry("expectedDatasetCount", 1)
            .containsEntry("excludedDatasetCount", 1)
            .containsEntry("allRequiredDatasetsProcessed", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSourceBlocksTraceCompletenessAndIsPresentInAccounting() {
        Map<String, Object> failure = Map.of(
            "datasetReference", "failed-source",
            "accountingStatus", "FAILED",
            "reason", "SOURCE_EXECUTION_FAILED");
        AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(
            new AnalysisEvidenceCoordinator.Projection(List.of(), List.of(failure)));
        AnalysisCoverageCoordinator coordinator = coordinator(evidence);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AnalysisCoverageCoordinator.CoverageBundle coverage = coordinator.analyze(
            request(metadata));

        assertThat(coverage.evidenceTraceComplete()).isFalse();
        assertThat(metadata).containsEntry("recordAnalysisAllSourcesFailed", true);
        assertThat((Map<String, Object>) metadata.get("datasetCompletionSnapshot"))
            .containsEntry("expectedDatasetCount", 1)
            .containsEntry("failedDatasetCount", 1)
            .containsEntry("allRequiredDatasetsProcessed", true);
    }

    private AnalysisCoverageCoordinator coordinator(AnalysisEvidenceCoordinator evidence) {
        return new AnalysisCoverageCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId", evidence,
            AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8));
    }

    private static Map<String,Object> validPlan() {
        return Map.of("version", "1.0", "intent", Map.of("type", "analysis", "goal", "Answer the question"),
            "context", Map.of("key_facts", List.of(), "assumptions", List.of(), "missing_info", List.of(), "constraints", List.of()),
            "plan", Map.of("steps", List.of(Map.of("id", 1, "action_type", "reasoning", "input", Map.of(), "depends_on", List.of()))));
    }

    @Test
    void failureObservationDescribesExecutionFailureRatherThanContentFormat() {
        var evidence = mock(AnalysisEvidenceCoordinator.class);
        when(evidence.project(any(), any())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(),
            List.of(Map.of("datasetReference", "opaque", "accountingStatus", "FAILED",
                "reason", "SOURCE_EXECUTION_FAILED", "executionStatus", "FAILED", "error", "SSH timeout"))));
        var adapter = mock(AgentRunResultAdapter.class);
        new AnalysisCoverageCoordinator(adapter, "agentRunId", evidence, AnalysisEvidenceSpillStore.disabled(),
            new AnalysisCoverageCoordinator.Configuration(8)).analyze(request(new LinkedHashMap<>()));
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(adapter).recordRuntimeObservation(any(), org.mockito.ArgumentMatchers.eq("agentRunId"),
            text.capture(), org.mockito.ArgumentMatchers.eq("analysis_summary_governance"), any());
        assertThat(text.getValue()).contains("SOURCE_EXECUTION_FAILED", "FAILED", "SSH timeout")
            .doesNotContain("结构化", "未返回");
    }

    private AnalysisCoverageCoordinator.Request request(Map<String, Object> metadata) {
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of(), 1);
        return new AnalysisCoverageCoordinator.Request(null, "question", result, Map.of(),
            metadata, () -> false, () -> {},
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            mock(DataAnalysisSummaryProtocol.class));
    }
}
