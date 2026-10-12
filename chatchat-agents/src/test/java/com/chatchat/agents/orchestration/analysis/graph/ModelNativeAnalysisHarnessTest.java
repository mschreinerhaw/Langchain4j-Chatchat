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
    @Test void modelReceivesRuntimeAdmissionStateAndExactMissingParameterReason() {
        var model = mock(ChatModel.class);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("recordAnalysisExcludedDatasets", List.of(Map.of(
            "datasetReference", "blocked-check", "toolName", "authorized_tool",
            "accountingStatus", "EXCLUDED", "executionStatus", "BLOCKED", "invoked", false,
            "reason", "SOURCE_EXECUTION_BLOCKED", "error", Map.of(
                "code", "TEMPLATE_REQUIRED_PARAMETERS_MISSING", "message", "container missing"))));
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenAnswer(call -> {
            dev.langchain4j.model.chat.request.ChatRequest request = call.getArgument(0);
            String prompt = ((dev.langchain4j.data.message.SystemMessage) request.messages().get(0)).text()
                + ((dev.langchain4j.data.message.UserMessage) request.messages().get(1)).singleText();
            assertThat(prompt).contains("\"executionStatus\":\"BLOCKED\"", "\"invoked\":false",
                "TEMPLATE_REQUIRED_PARAMETERS_MISSING", "container missing", "SOURCE_EXECUTION_BLOCKED",
                "accountingStatus is dataset bookkeeping", "not a transport failure");
            assertThat(prompt).doesNotContain("SOURCE_TRANSPORT_ONLY_NOT_ANALYTICAL_SUFFICIENCY");
            String response = ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                "decision", Map.of("action", "PARTIAL_COMPLETE"), "output_type", "final",
                "plan", Map.of("version", "1.0", "intent", Map.of("type", "analysis", "goal", "Explain returned evidence"),
                    "context", Map.of("key_facts", List.of(), "assumptions", List.of(), "missing_info", List.of(),
                        "constraints", List.of()), "plan", Map.of("steps", List.of(Map.of(
                            "id", 1, "action_type", "reasoning", "input", Map.of(), "depends_on", List.of())))),
                "content", "One check was blocked before invocation because container was missing."));
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from(response)).build();
        });
        assertThat(execute(1, sources(), model, metadata).markdown()).contains("blocked before invocation");
    }
    @Test void omittedStringPreviewHasAnExecutableOriginalValueLocator() {
        var access = new BoundedAnalysisEvidence();
        var sources = new LinkedHashMap<String, Dataset>();
        String original = "start-" + "x".repeat(7000) + "-end";
        sources.put("source", new Dataset("source", Map.of(), List.of(Map.of("opaqueField", original))));
        var prepared = new BoundedAnalysisEvidence.Prepared(List.of(), "fingerprint", sources, false);
        var request = Map.of("operation", "READ_RECORDS", "datasetReference", "source", "fromRecord", 1, "limit", 1);
        var receipt = access.read(prepared, List.of(request), () -> {}, null, scope,
            AnalysisEvidenceSpillStore.disabled(), "Analyze", new LinkedHashMap<>());
        var row = (Map<?, ?>) ((List<?>) receipt.get(0).get("rows")).get(0);
        var hint = (Map<?, ?>) ((List<?>) row.get("readHints")).get(0);
        assertThat(hint.get("totalChars")).isEqualTo(original.length());
        var next = access.read(prepared, List.of(hint.get("request")), () -> {}, null, scope,
            AnalysisEvidenceSpillStore.disabled(), "Analyze", new LinkedHashMap<>());
        assertThat(next.get(0)).containsEntry("text", original.substring(0, 3000))
            .containsEntry("hasMore", true).containsEntry("nextChar", 3000);
    }
    @Test void absentProjectionFieldIsNotReportedAsMissingSourceData() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(new org.mockito.stubbing.Answer<String>() {
            int turn;
            public String answer(org.mockito.invocation.InvocationOnMock call) {
                if (++turn == 1) return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                    "decision", Map.of("action", "CONTINUE"), "evidenceRequests", List.of(Map.of(
                        "operation", "READ_RECORDS", "datasetReference", "source", "fromRecord", 1,
                        "limit", 1, "fields", List.of("content")))));
                assertThat((String) call.getArgument(0)).contains("\"availableFields\":[\"value\"]",
                    "\"absentInReturnedItems\":[\"content\"]", "\"absentFieldCount\":1");
                return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                    "decision", Map.of("action", "COMPLETE"), "output_type", "final", "content", "Limited report"));
            }
        });
        assertThat(execute(3, sources(), model, new LinkedHashMap<>()).markdown()).isEqualTo("Limited report");
    }
    @Test void plannedTaskUsesOnlyV2AndMeasuresWholePrompt() {
        var model = mock(ChatModel.class);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("analysisInterpretationPlan", Map.of("version", "original-plan", "steps", List.of()));
        when(model.chat(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertThat(prompt).startsWith("Plan before execution.")
                .contains("L1.submittedPlan", "original-plan", "workspace.changeReason",
                    "inside the content string", "\"READ_CONTEXT\"", "limit:1..20")
                .doesNotContain("model_native_analysis.v1", "WAIT", "PUBLISH", "reportMarkdown",
                    "You may finish immediately", "plain Markdown", "plan.plan.change_reason");
            assertThat(ModelAnalysisPrompt.CORE.length() + ModelAnalysisPrompt.protocol(true).length())
                .isLessThan(4000);
            var sizes = (Map<?, ?>) metadata.get("harnessPromptSize");
            assertThat(sizes.get("fullChars")).isEqualTo(prompt.length());
            assertThat(((Number) sizes.get("layersChars")).intValue()).isPositive();
            return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                "decision", Map.of("action", "COMPLETE"), "output_type", "final", "content", "Final"));
        });
        assertThat(execute(3, sources(), model, metadata).markdown()).isEqualTo("Final");
        assertThat(metadata).containsEntry("publicationState", "REQUESTED");
    }

    @Test void legacyPromptHasOneProtocolWhileAdapterStillAcceptsPlainReports() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("model_native_analysis.v1",
                "inside the reportMarkdown string").doesNotContain("model_native_analysis.v2", "WAIT", "PUBLISH");
            return "Legacy report";
        });
        assertThat(execute(3, sources(), model, new LinkedHashMap<>()).markdown()).isEqualTo("Legacy report");
    }
    @Test void protocolCorrectionPreservesThePreviousExecutionReceipt() {
        var model = mock(ChatModel.class); var turn = new AtomicInteger();
        when(model.chat(anyString())).thenAnswer(call -> {
            int number = turn.incrementAndGet();
            if (number == 1) return v2("CONTINUE", "", List.of(Map.of("operation", "READ_TEXT",
                "datasetReference", "source", "record", 1, "field", "text")));
            if (number == 2) return "{\"schemaVersion\":null}";
            assertThat((String) call.getArgument(0)).contains("INVALID_TURN_PROTOCOL",
                "\"receipts\":[{\"datasetReference\":\"source\",\"recordRef\":\"source.records[1]\",\"field\":\"text\"");
            return v2("COMPLETE", "Supported result", List.of());
        });
        execute(4, List.of(new Dataset("source", Map.of(), List.of(Map.of("text", "EXACT FACT")))), model,
            new LinkedHashMap<>(Map.of("modelAnalysisProtocol", "model_native_analysis.v2")));
    }
    @Test void missingSchemaVersionIsFeedbackInsteadOfAnUnhandledException() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"plan\":{\"objective\":\"Discover facts\"}}",
            "{\"schemaVersion\":null}", "Model corrected report");
        var meta = new LinkedHashMap<String,Object>();
        assertThat(execute(4, sources(), model, meta).markdown()).isEqualTo("Model corrected report");
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).chat(prompts.capture());
        assertThat(prompts.getAllValues().get(1)).contains("INVALID_TURN_PROTOCOL", "Missing or unsupported schemaVersion");
    }
    @Test void freeReportPromptOffersOptionalVisualizationProtocolAndPreservesRequestedJson() {
        var model = mock(ChatModel.class);
        String authored = "# Example\n```json\n{\"example\":42}\n```\n\n| Value |\n| --- |\n| 42 |";
        when(model.chat(any(String.class))).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertThat(prompt).contains("VisualizationCapabilityInjector:", "json:report-block",
                "reportBlockSchema", "allowedChartTypes", "Put the narrative and optional");
            assertThat(prompt).contains("Markdown tables",
                "READ_RECORDS", "\"datasetReference\":\"source\"");
            return authored;
        });
        var metadata = new LinkedHashMap<String,Object>();
        assertThat(execute(3, sources(), model, metadata).markdown()).isEqualTo(authored);
        assertThat(metadata).containsKey("visualizationCapabilities");
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void recordsFailedRequestBeforePropagatingCancellation() {
        var model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "CONTINUE"),
            "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "source", "fromRecord", 1, "limit", 1)))));
        var pending = new java.util.concurrent.atomic.AtomicBoolean();
        var observed = new ArrayList<Map<String,Object>>();
        var meta = new LinkedHashMap<String,Object>();
        assertThatThrownBy(() -> new ModelNativeAnalysisHarness(3).execute("Analyze", sources(), model, scope,
            AnalysisEvidenceSpillStore.disabled(), meta,
            () -> { if (pending.get()) throw new java.util.concurrent.CancellationException(); },
            event -> { observed.add(event); if ("EVIDENCE_REQUEST_STARTED".equals(event.get("stage"))) pending.set(true); }))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(observed.get(observed.size() - 1)).containsEntry("stage", "EVIDENCE_REQUEST_FAILED")
            .containsEntry("eventState", "FAILED");
        assertThat(meta).containsEntry("executionStopReason", "CANCELLED");
        verify(model, times(1)).chat(any(String.class));
    }
    @Test void streamsPublicExplorationBeforeReadsAndPreservesModelAutonomy() {
        var model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "CONTINUE"),
            "workspace", Map.of("notes", "PRIVATE_NOTES", "findings", List.of("Public observation")),
            "progress", Map.of("summary", "Reading source"),
            "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "source", "fromRecord", 1, "limit", 1)))),
            ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
                "decision", Map.of("action", "COMPLETE"), "output_type", "final", "content", "Model report")));
        var observed = new ArrayList<Map<String,Object>>();
        var meta = new LinkedHashMap<String,Object>();
        var result = new ModelNativeAnalysisHarness(3).execute("Analyze", sources(), model, scope,
            AnalysisEvidenceSpillStore.disabled(), meta, () -> {}, observed::add);
        assertThat(result.markdown()).isEqualTo("Model report");
        assertThat(observed.stream().filter(e -> "RUNNING".equals(e.get("eventState")))
            .map(e -> e.get("stage"))).containsExactly("MODEL_RETURNED", "EVIDENCE_REQUEST_STARTED",
                "EVIDENCE_REQUEST_FINISHED", "MODEL_RETURNED");
        assertThat(observed.toString()).doesNotContain("PRIVATE_NOTES");
        var started = observed.stream().filter(e -> "EVIDENCE_REQUEST_STARTED".equals(e.get("stage"))).findFirst().orElseThrow();
        assertThat(started.toString()).contains("STARTED", "Public observation", "Reading source");
        assertThat(started).containsEntry("completedRequestCount", 0);
        var finished = observed.stream().filter(e -> "EVIDENCE_REQUEST_FINISHED".equals(e.get("stage"))).findFirst().orElseThrow();
        assertThat(finished).containsEntry("completedRequestCount", 1);
        verify(model, times(2)).chat(any(String.class));
    }
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
                "report quality", "SOURCE_EXECUTION_ONLY_NOT_ANALYTICAL_SUFFICIENCY", "failed-source");
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
    @Test void explorationObservationsExposeExplicitDecisionsAndDeclaredStateWithoutPrivateWorkspaceNotes() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "COMPLETE"), "reportMarkdown", "Draft",
            "workspace", Map.of("notes", "private working notes", "state", Map.of("findings", List.of("explicit finding"))))));
        var observations = new ArrayList<Map<String,Object>>();
        new ModelNativeAnalysisHarness(3).execute("Goal", sources(), model, scope,
            AnalysisEvidenceSpillStore.disabled(), new LinkedHashMap<>(), () -> {}, observations::add);
        var completed = observations.stream().filter(span -> "COMPLETED".equals(span.get("eventState"))).findFirst().orElseThrow();
        assertThat(((Map<?,?>)completed.get("modelDecision")).get("action")).isEqualTo("COMPLETE");
        assertThat(completed).containsEntry("publicationState", "NOT_REQUESTED");
        assertThat(completed.toString()).contains("explicit finding").doesNotContain("private working notes");
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
        assertThat(meta).containsEntry("modelAnalysisOutput", "Draft only").containsEntry("executionState", "STOPPED")
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
            assertThat(meta).containsEntry("modelAnalysisOutput", "Retained draft").containsEntry("publicationState", "NOT_REQUESTED")
                .containsEntry("executionState", "STOPPED").containsEntry("executionStopReason",
                    failure instanceof com.chatchat.agents.orchestration.model.AgentDeadlineExceededException ? "TIMEOUT" : "CANCELLED");
            assertThat(((Map<?,?>)meta.get("modelDecision")).get("action")).isEqualTo("CONTINUE");
        }
    }
    @Test void v2BindsActualContentInsteadOfModelSuppliedPublicationVersion() {
        var model = mock(ChatModel.class); var meta = new LinkedHashMap<String,Object>();
        when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "PUBLISH"), "reportMarkdown", "Report",
            "publication", Map.of("reportSha256", "wrong", "evidenceSnapshotRef", "wrong"))));
        var result = execute(3, sources(), model, meta);
        assertThat(result.markdown()).isEqualTo("Report");
        assertThat(meta).containsEntry("publicationState", "REQUESTED");
        var binding = (Map<?,?>)meta.get("modelPublicationRequest");
        assertThat(binding.get("authority")).isEqualTo("RUNTIME");
        assertThat(binding.get("reportSha256")).isEqualTo(ModelProtocolJson.sha256Hex("Report"));
        assertThat(binding.get("evidenceSnapshotRef")).isEqualTo(meta.get("modelEvidenceSnapshotRef"));
        assertThat(meta.get("modelPublicationReferenceAudit").toString()).contains("LEGACY_REFERENCE_IGNORED");
    }

    @Test void modelDeclaresContentTypeIndependentlyOfCompletionAndPublicationActions() {
        for (String type : List.of("final", "draft", "intermediate")) {
            var model = mock(ChatModel.class);
            when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of(
                "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "PARTIAL_COMPLETE"),
                "output_type", type, "content", "Limited model response",
                "evidenceAssessment", Map.of("evidenceStatus", "PARTIAL", "missingEvidence", List.of("chunk2")))));
            var metadata = new LinkedHashMap<String,Object>(); execute(3, sources(), model, metadata);
            assertThat(metadata).containsEntry("modelAnalysisOutput", "Limited model response").doesNotContainKey("modelNativeReportDraft");
            assertThat(((Map<?,?>)metadata.get("modelOutput")).get("type")).isEqualTo(type.toUpperCase(java.util.Locale.ROOT));
            assertThat(metadata.get("publicationState")).isEqualTo(type.equals("final") ? "REQUESTED" : "NOT_REQUESTED");
            assertThat(((Map<?,?>)metadata.get("modelDecision")).get("action")).isEqualTo("PARTIAL_COMPLETE");
            verify(model).chat(anyString());
        }
    }
    @Test void budgetStopDoesNotClassifyUndeclaredContentAsDraftOrFinal() {
        var model=mock(ChatModel.class); when(model.chat(anyString())).thenReturn(v2("CONTINUE", "Saved output", List.of()));
        var metadata=new LinkedHashMap<String,Object>(); execute(1, sources(), model, metadata);
        assertThat(((Map<?,?>)metadata.get("modelOutput")).get("type")).isEqualTo("UNDECLARED");
        assertThat(metadata).containsEntry("executionStopReason", "RESOURCE_BUDGET_EXHAUSTED");
    }

}
