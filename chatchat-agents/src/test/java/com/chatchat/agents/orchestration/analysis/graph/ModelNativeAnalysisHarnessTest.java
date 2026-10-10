package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelNativeAnalysisHarnessTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");
    private List<Dataset> sources() { return List.of(new Dataset("source", Map.of(), List.of(Map.of("value", 42)))); }
    private ModelNativeAnalysisHarness.Result execute(int turns, List<Dataset> datasets, ChatModel model, Map<String,Object> meta) {
        return new ModelNativeAnalysisHarness(turns).execute("Compare the evidence", datasets, model, scope,
            AnalysisEvidenceSpillStore.disabled(), meta, () -> {}, span -> {});
    }
    @Test void publishesModelChosenProseWithoutMandatoryFindingsOrQualityRepair() {
        var model = mock(ChatModel.class); when(model.chat(any(String.class))).thenReturn("# Findings\nA model-chosen interpretation.");
        var meta = new LinkedHashMap<String,Object>();
        var datasets = new ArrayList<>(sources()); datasets.add(new Dataset("other", Map.of(), List.of(Map.of("value", 2))));
        var result = execute(8, datasets, model, meta);
        assertThat(result.markdown()).isEqualTo("# Findings\nA model-chosen interpretation.");
        assertThat(result.datasetReferences()).containsExactly("source", "other");
        assertThat(meta).containsEntry("harnessModelCalls", 1).containsEntry("harnessStopReason", "MODEL_COMPLETED")
            .doesNotContainKeys("unifiedAnalysisDatasetCoverageRepairAttempts", "semanticClaimPreflightFailed");
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void modelChoosesPartialScopeAndPublishesEvenWhenReferenceDiagnosticsFindAGap() {
        var model = mock(ChatModel.class);
        var assessment = Map.of("evidenceStatus", "PARTIAL", "missingEvidence", List.of("source#chunk2"),
            "conclusionScope", "SNAPSHOT_ONLY", "requiresReanalysis", false,
            "claims", List.of(Map.of("claimId", "C1", "claim", "The observed window has low activity.",
                "references", List.of(Map.of("datasetReference", "source", "record", 1, "fieldPath", List.of("unknown"))))));
        when(model.chat(any(String.class))).thenAnswer(call -> {
            assertThat((String)call.getArgument(0)).contains("cumulative counters from interval deltas",
                "publication", "SOURCE_TRANSPORT_ONLY_NOT_ANALYTICAL_SUFFICIENCY", "failed-source");
            return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", true,
                "reportMarkdown", "The observed window has low activity; another chunk is missing.", "evidenceAssessment", assessment));
        });
        var meta = new LinkedHashMap<String, Object>();
        meta.put("recordAnalysisExcludedDatasets", List.of(Map.of("datasetReference", "failed-source", "accountingStatus", "FAILED")));
        var result = execute(3, sources(), model, meta);
        assertThat(result.markdown()).isEqualTo("The observed window has low activity; another chunk is missing.");
        var audit = (Map<?, ?>) meta.get("modelEvidenceAssessmentAudit");
        assertThat(audit.get("assessment")).isEqualTo(assessment);
        assertThat(audit.get("publicationEffect")).isEqualTo("NONE");
        assertThat(audit.toString()).contains("FIELD_PATH_NOT_FOUND", "reportSha256");
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void finalPlainReportDoesNotInheritAnEarlierDraftAssessment() {
        var model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1",
            "completed", false, "reportMarkdown", "Draft", "evidenceAssessment", Map.of("evidenceStatus", "COMPLETE"))), "Final report.");
        var meta = new LinkedHashMap<String, Object>();
        assertThat(execute(3, sources(), model, meta).markdown()).isEqualTo("Final report.");
        assertThat(((Map<?, ?>) meta.get("modelEvidenceAssessmentAudit")).get("status")).isEqualTo("NOT_SUPPLIED");
        assertThat(((Map<?, ?>) meta.get("modelEvidenceAssessmentAudit")).get("turn")).isEqualTo(2);
        var history = (List<?>) meta.get("modelEvidenceAssessmentHistory");
        assertThat(history).hasSize(2);
        assertThat(((Map<?, ?>) history.get(0)).get("status")).isEqualTo("RECORDED");
        assertThat(((Map<?, ?>) history.get(1)).get("status")).isEqualTo("NOT_SUPPLIED");
        verify(model, times(2)).chat(any(String.class));
    }
    @Test void declaredOverclaimsAndReanalysisPreferenceDoNotTriggerRuntimeRepair() {
        var model = mock(ChatModel.class);
        var assessment = Map.of("evidenceStatus", "COMPLETE", "missingEvidence", List.of("source#chunk2"),
            "requiresReanalysis", true, "claims", List.of(Map.of("claimId", "C1",
                "scope", Map.of("temporalScope", "LONG_TERM", "precision", "EXACT", "guaranteeScope", "SYSTEM"),
                "references", List.of(Map.of("datasetReference", "source", "record", 1, "fieldPath", List.of("value"),
                    "semantics", Map.of("temporalScope", "SNAPSHOT", "precision", "ROUNDED", "guaranteeScope", "COMPONENT"))))));
        when(model.chat(any(String.class))).thenReturn(ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v1", "completed", true,
            "reportMarkdown", "The model chooses to publish this conclusion.", "evidenceAssessment", assessment)));
        var meta = new LinkedHashMap<String, Object>();
        assertThat(execute(8, sources(), model, meta).markdown()).isEqualTo("The model chooses to publish this conclusion.");
        assertThat(meta).containsEntry("harnessStopReason", "MODEL_COMPLETED").doesNotContainKey("evidenceClaimValidation");
        assertThat(((Map<?, ?>) meta.get("modelEvidenceAssessmentAudit")).get("assessment")).isEqualTo(assessment);
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void readsOriginalTextAcrossWindowsAndRetainsModelNotes() {
        var datasets = List.of(new Dataset("source", Map.of(), List.of(Map.of("text", "A".repeat(5500) + "SOURCE_END", "large", "B".repeat(50000)))));
        var calls = new AtomicInteger(); var model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0); int turn = calls.incrementAndGet();
            if (turn == 1) assertThat(prompt).doesNotContain("\"text\":\"" + "A".repeat(5500));
            if (turn == 2) assertThat(prompt).contains("\"nextChar\":3000", "First window reviewed");
            if (turn == 3) { assertThat(prompt).contains("SOURCE_END", "\"hasMore\":false", "First window reviewed"); return "# Findings\nThe full original field was read."; }
            return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", false,
                "workspace", Map.of("notes", "First window reviewed"), "evidenceRequests", List.of(Map.of(
                    "operation", "READ_TEXT", "datasetReference", "source", "record", 1, "field", "text", "fromChar", (turn - 1) * 3000, "maxChars", 3000))));
        });
        var meta = new LinkedHashMap<String,Object>();
        assertThat(execute(8, datasets, model, meta).modelCalls()).isEqualTo(3);
        assertThat(meta).containsKey("harnessWorkspace").containsKey("harnessTrace");
    }
    @Test void rejectedCrossRunReadDoesNotBecomeMissingEvidenceOrDiscardProse() {
        var model = mock(ChatModel.class); var calls = new AtomicInteger();
        when(model.chat(any(String.class))).thenAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                assertThat((String)call.getArgument(0)).contains("REQUEST_REJECTED", "UNCHANGED");
                return "Supported original analysis.";
            }
            return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", false,
                "reportMarkdown", "Supported original analysis.", "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS",
                    "datasetReference", "another-run", "fromRecord", 1, "limit", 1))));
        });
        assertThat(execute(8, sources(), model, new LinkedHashMap<>()).markdown()).isEqualTo("Supported original analysis.");
    }
    @Test void resourceBudgetPreservesLatestModelReportWithoutForcingAnotherAnalysis() {
        var model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1",
            "completed", false, "reportMarkdown", "Existing findings.", "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS",
                "datasetReference", "source", "fromRecord", 1, "limit", 1)))));
        var meta = new LinkedHashMap<String,Object>();
        assertThat(execute(1, sources(), model, meta).markdown()).isEqualTo("Existing findings.");
        assertThat(meta).containsEntry("harnessStopReason", "RESOURCE_BUDGET_EXHAUSTED");
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void turnBudgetCanExceedTheOldThreeDecisions() {
        var model = mock(ChatModel.class); var count = new AtomicInteger();
        when(model.chat(any(String.class))).thenAnswer(call -> count.incrementAndGet() == 5 ? "Chosen final report." :
            ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", false,
                "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "source", "fromRecord", 1, "limit", 1)))));
        assertThat(execute(8, sources(), model, new LinkedHashMap<>()).modelCalls()).isEqualTo(5);
    }
    @Test void resumesOnlyAnIdenticalScopedInputCheckpoint() {
        var stored = new HashMap<String,String>();
        var checkpoints = mock(AnalysisEvidenceSpillStore.class);
        when(checkpoints.readCheckpoint(any(), anyString(), anyString())).thenAnswer(call ->
            Optional.ofNullable(stored.get(call.getArgument(0).toString() + call.getArgument(1) + call.getArgument(2))));
        doAnswer(call -> { stored.put(call.getArgument(0).toString() + call.getArgument(1) + call.getArgument(2), call.getArgument(3)); return null; })
            .when(checkpoints).checkpoint(any(), anyString(), anyString(), anyString());
        var model = mock(ChatModel.class); when(model.chat(any(String.class))).thenReturn("Original report.");
        var harness = new ModelNativeAnalysisHarness(8);
        var first = harness.execute("Question", sources(), model, scope, checkpoints, new LinkedHashMap<>(), () -> {}, span -> {});
        var second = harness.execute("Question", sources(), model, scope, checkpoints, new LinkedHashMap<>(), () -> {}, span -> {});
        var changed = harness.execute("Changed question", sources(), model, scope, checkpoints, new LinkedHashMap<>(), () -> {}, span -> {});
        assertThat(first.modelCalls()).isEqualTo(1);
        assertThat(second.modelCalls()).isZero();
        assertThat(second.markdown()).isEqualTo(first.markdown());
        assertThat(changed.modelCalls()).isEqualTo(1);
        verify(model, times(2)).chat(any(String.class));
    }
    @Test void rejectsUnregisteredExtractorWithoutAnExtraModelInvocation() {
        var model = mock(ChatModel.class); var count = new AtomicInteger();
        when(model.chat(any(String.class))).thenAnswer(call -> {
            if (count.incrementAndGet() == 2) {
                assertThat((String)call.getArgument(0)).contains("Operation is not registered");
                return "Final report.";
            }
            return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", false,
                "evidenceRequests", List.of(Map.of("operation", "EXTRACT_TEXT", "datasetReference", "source", "record", 1, "field", "value"))));
        });
        assertThat(execute(8, sources(), model, new LinkedHashMap<>()).modelCalls()).isEqualTo(2);
        verify(model, times(2)).chat(any(String.class));
    }

    private String v2(String action, String draft, List<Map<String,Object>> requests) {
        return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", action), "reportMarkdown", draft, "evidenceRequests", requests));
    }
    @Test void v2ContinuesWithoutNewEvidenceAndPublishesOnlyOnModelRequest() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(v2("CONTINUE", "First draft", List.of()),
            v2("CONTINUE", "Revised draft", List.of()), v2("PUBLISH", "Chosen report", List.of()));
        var meta = new LinkedHashMap<String,Object>();
        assertThat(execute(4, sources(), model, meta).markdown()).isEqualTo("Chosen report");
        assertThat(meta).containsEntry("publicationState", "REQUESTED").containsEntry("executionStopReason", "MODEL_DECISION");
        assertThat(((Map<?,?>)meta.get("modelPublicationRequest")).get("reportSha256"))
            .isEqualTo(ModelProtocolJson.sha256Hex("Chosen report"));
        verify(model, times(3)).chat(anyString());
    }
    @Test void v2CompletionAndWaitRetainDraftWithoutRequestingPublication() {
        for (String action : List.of("COMPLETE", "PARTIAL_COMPLETE", "WAIT")) {
            var model = mock(ChatModel.class); when(model.chat(anyString())).thenReturn(v2(action, "Private draft", List.of()));
            var meta = new LinkedHashMap<String,Object>();
            assertThat(execute(3, sources(), model, meta).markdown()).isEqualTo("Private draft");
            assertThat(meta).containsEntry("publicationState", "NOT_REQUESTED");
            assertThat(((Map<?,?>)meta.get("modelDecision")).get("action")).isEqualTo(action);
            if (action.equals("WAIT")) assertThat(meta).containsEntry("executionStopReason", "WAIT_UNSUPPORTED");
            verify(model).chat(anyString());
        }
    }
    @Test void v2BudgetExhaustionPreservesDraftAndContinueDecisionWithoutPublishing() {
        var model = mock(ChatModel.class); when(model.chat(anyString())).thenReturn(v2("CONTINUE", "Draft only", List.of()));
        var meta = new LinkedHashMap<String,Object>(); execute(1, sources(), model, meta);
        assertThat(meta).containsEntry("modelNativeReportDraft", "Draft only").containsEntry("executionState", "STOPPED")
            .containsEntry("executionStopReason", "RESOURCE_BUDGET_EXHAUSTED").containsEntry("publicationState", "NOT_REQUESTED");
        assertThat(((Map<?,?>)meta.get("modelDecision")).get("action")).isEqualTo("CONTINUE");
    }
    @Test void v2CannotImplicitlyPublishByReturningLegacyMarkdown() {
        var model = mock(ChatModel.class); when(model.chat(anyString())).thenReturn(v2("CONTINUE", "Private", List.of()), "Accidental legacy report");
        var meta = new LinkedHashMap<String,Object>();
        assertThat(execute(2, sources(), model, meta).markdown()).isEqualTo("Private");
        assertThat(meta).containsEntry("publicationState", "NOT_REQUESTED");
    }
    @Test void v2ExplicitPublicationCanDeclareGapsWithoutRuntimeRepair() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "PUBLISH"), "reportMarkdown", "Limited report",
            "evidenceAssessment", Map.of("evidenceStatus", "PARTIAL", "missingEvidence", List.of("chunk2")))));
        var meta = new LinkedHashMap<String,Object>(); execute(3, sources(), model, meta);
        assertThat(meta).containsEntry("publicationState", "REQUESTED"); verify(model).chat(anyString());
    }
    @Test void v2CancellationAndTimeoutRetainLastModelDecisionAndDraft() {
        for (java.util.concurrent.CancellationException failure : List.of(new java.util.concurrent.CancellationException("cancel"),
            new com.chatchat.agents.orchestration.model.AgentDeadlineExceededException("deadline"))) {
            var model = mock(ChatModel.class); var meta = new LinkedHashMap<String,Object>();
            when(model.chat(anyString())).thenReturn(v2("CONTINUE", "Retained draft", List.of())).thenThrow(failure);
            assertThatThrownBy(() -> execute(3, sources(), model, meta)).isSameAs(failure);
            assertThat(meta).containsEntry("modelNativeReportDraft", "Retained draft").containsEntry("publicationState", "NOT_REQUESTED")
                .containsEntry("executionState", "STOPPED").containsEntry("executionStopReason",
                    failure instanceof com.chatchat.agents.orchestration.model.AgentDeadlineExceededException ? "TIMEOUT" : "CANCELLED");
            assertThat(((Map<?,?>)meta.get("modelDecision")).get("action")).isEqualTo("CONTINUE");
        }
    }
    @Test void v2RejectsWrongPublicationVersion() {
        var model = mock(ChatModel.class); var meta = new LinkedHashMap<String,Object>();
        when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "PUBLISH"), "reportMarkdown", "Report",
            "publication", Map.of("reportSha256", "wrong", "evidenceSnapshotRef", "wrong"))));
        assertThatThrownBy(() -> execute(3, sources(), model, meta)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
        assertThat(meta).containsEntry("publicationState", "REJECTED").containsEntry("executionStopReason", "GOVERNANCE_REJECTION");
    }

}
