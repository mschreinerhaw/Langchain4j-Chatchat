package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.nodes.analysis.AnalysisNodeProtocol;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UnifiedQuestionAnalysisGraphTest {
    @Test void acceptsJsonContractWrappedInModelReasoningText() {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                return "I checked the evidence.\n```json\n" + product("dataset1") + "\n```\nDone.";
            }
        };
        var metadata = new LinkedHashMap<String, Object>();

        var outcomes = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});

        assertThat(outcomes.get("dataset1").summary().content()).contains("Returned value is 1");
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 1)
            .doesNotContainKey("unifiedAnalysisContractRepairAttempted");
    }

    @Test void repairsAnInvalidInitialFindingContractInsteadOfDiscardingReturnedRows() {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                return calls.incrementAndGet() == 1
                    ? "The value is one, but I did not follow the protocol."
                    : product("dataset1");
            }
        };
        var metadata = new LinkedHashMap<String, Object>();

        var outcomes = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});

        assertThat(calls.get()).isEqualTo(2);
        assertThat(outcomes.get("dataset1").summary().content()).contains("Returned value is 1");
        assertThat(metadata)
            .containsEntry("unifiedAnalysisContractRepairAttempted", true)
            .containsEntry("unifiedAnalysisContractRepairSucceeded", true)
            .containsEntry("unifiedAnalysisModelCalls", 2);
    }

    @Test void failedOptionalModelCallRetainsCandidatesForEvidenceValidation() throws Exception {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked") Map<String, Object> response = mapper.readValue(product("dataset1"), Map.class);
        response.put("evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "dataset1", "fromRecord", 1, "limit", 1)));
        var model = org.mockito.Mockito.mock(ChatModel.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(mapper.writeValueAsString(response)).thenThrow(new IllegalStateException("transport unavailable"));
        var metadata = new LinkedHashMap<String, Object>();
        var outcomes = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(outcomes.get("dataset1").summary().content()).contains("Returned value is 1");
        assertThat(metadata).containsEntry("unifiedAnalysisSupplementFailure", "IllegalStateException")
            .containsEntry("unifiedAnalysisStatus", "COMPLETED_WITH_LIMITATIONS");
        for (RuntimeException stop : List.of(new java.util.concurrent.CancellationException("cancelled"),
            new com.chatchat.agents.orchestration.model.AgentDeadlineExceededException("deadline"))) {
            org.mockito.Mockito.reset(model);
            org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mapper.writeValueAsString(response)).thenThrow(stop);
            assertThatThrownBy(() -> new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
                new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), new LinkedHashMap<>(), () -> {}))
                .isInstanceOf(stop.getClass());
        }
    }

    @Test void initialAnalysisHonorsConfiguredRequestDeadline() {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var delegate = org.mockito.Mockito.mock(ChatModel.class);
        var model = new com.chatchat.agents.orchestration.model.DeadlineAwareChatModel(delegate, () -> 0L);
        assertThatThrownBy(() -> new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets,
            model, scope, new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), new LinkedHashMap<>(), () -> {}))
            .hasMessageContaining("execution time budget exhausted");
        org.mockito.Mockito.verifyNoInteractions(delegate);
    }

    @Test void supplementaryAnalysisWaitsForCompletedFindings() throws Exception {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked") Map<String, Object> response = mapper.readValue(product("dataset1"), Map.class);
        response.put("evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "dataset1", "fromRecord", 1, "limit", 1)));
        var calls = new AtomicInteger();
        var model = org.mockito.Mockito.mock(ChatModel.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) return mapper.writeValueAsString(response);
            Thread.sleep(250);
            return product("dataset1");
        });
        var metadata = new LinkedHashMap<String, Object>();
        var outcomes = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(outcomes.get("dataset1").summary().content()).contains("Returned value is 1");
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 2)
            .containsEntry("unifiedAnalysisFindingCount", 1);
    }

    @Test void rejectedOptionalReadPreservesFindingsFromFortyOneReturnedRows() throws Exception {
        var rows = java.util.stream.IntStream.rangeClosed(1, 41).mapToObj(i -> Map.<String, Object>of("VALUE", i)).toList();
        var datasets = List.of(new Dataset("dataset1", Map.of(), rows));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked") Map<String, Object> response = mapper.readValue(product("dataset1"), Map.class);
        response.put("evidenceRequests", List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "dataset1", "fromRecord", 0, "limit", 10000)));
        var model = org.mockito.Mockito.mock(ChatModel.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(mapper.writeValueAsString(response));
        var metadata = new LinkedHashMap<String, Object>();
        var results = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(results.get("dataset1").summary().content()).contains("Returned value is 1");
        assertThat(metadata).containsEntry("unifiedEvidenceRejectedRequestCount", 1)
            .containsEntry("unifiedAnalysisStatus", "COMPLETED_WITH_LIMITATIONS");
        assertThat(metadata.get("unifiedEvidenceReadAudit").toString())
            .contains("fromRecord=0", "limit=10000", "availableRecordCount=41", "status=REQUEST_REJECTED");
        org.mockito.Mockito.verify(model).chat(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void rejectedReadWithoutFindingsIsReturnedToModelForCorrection() {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var count = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                if (count.incrementAndGet() == 1) return "{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"evidenceRequests\":[{\"operation\":\"READ_RECORDS\",\"datasetReference\":\"dataset1\",\"fromRecord\":0,\"limit\":10}]}";
                assertThat(prompt).contains("REQUEST_REJECTED", "availableRecordCount", "not an empty dataset");
                return product("dataset1");
            }
        };
        var results = new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), new LinkedHashMap<>(), () -> {});
        assertThat(count.get()).isEqualTo(2);
        assertThat(results.get("dataset1").summary().content()).contains("Returned value is 1");
    }

    @Test void mixedReadBatchRetainsSuccessfulEvidenceAndReportsRejectedRange() {
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        var count = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                if (count.incrementAndGet() == 1) return ModelProtocolJson.compact(Map.of(
                    "schemaVersion", "unified_question_analysis.v1", "findings", List.of(), "evidenceRequests", List.of(
                        Map.of("operation", "READ_RECORDS", "datasetReference", "dataset1", "fromRecord", 1, "limit", 1),
                        Map.of("operation", "READ_RECORDS", "datasetReference", "dataset1", "fromRecord", 2, "limit", 1))));
                assertThat(prompt).contains("dataset1.records[1]", "REQUEST_REJECTED", "availableRecordCount");
                return product("dataset1");
            }
        };
        var metadata = new LinkedHashMap<String, Object>();
        new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(metadata).containsEntry("unifiedAnalysisFindingCount", 1);
        assertThat(metadata.get("unifiedEvidenceReadAudit").toString()).contains("status=ACCEPTED", "status=REQUEST_REJECTED");
    }
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("t", "u", "r", "q", "c");

    @Test void fiveDatasetsProduceOneGlobalModelCallAfterComputation() {
        List<Dataset> datasets = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
            new Dataset("dataset" + index, Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", index)))).toList();
        var computed = new AtomicInteger();
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                calls.incrementAndGet();
                assertThat(computed.get()).isEqualTo(1);
                assertThat(prompt).contains("ONE_QUESTION_ALL_BOUND_DATASETS", "dataset1", "dataset5");
                return product("dataset1");
            }
        };
        var metadata = new LinkedHashMap<String, Object>();
        var outcomes = new UnifiedQuestionAnalysisGraph().execute("Analyze all returned data", datasets,
            () -> { computed.incrementAndGet(); return datasets; }, model, scope, new AnalysisNodeProtocol(),
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(calls.get()).isEqualTo(1);
        assertThat(outcomes).hasSize(5);
        assertThat(outcomes.get("dataset1").summary().datasetSummary().position())
            .containsEntry("datasetReference", "dataset1");
        assertThat(outcomes.get("dataset5").summary().content()).doesNotContain("value is 1");
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 1);
        assertThat(metadata).containsEntry("unifiedAnalysisStatus", "COMPLETED_WITH_LIMITATIONS");
        assertThat(metadata.get("unifiedAnalysisGraphNodes").toString())
            .contains("analysis_planning", "data_computation", "generate_findings", "validate_findings");
    }

    @Test void semanticMetadataTriggersOnePromptSynthesisBeforeUnifiedFindings() {
        var datasets = List.of(new Dataset("customer_trades", Map.of(
            "source", Map.of("displayName", "客户交易", "description", "客户成交明细"),
            "schema", Map.of("fields", List.of(Map.of("name", "amount", "label", "成交金额")))),
            List.<Map<String, Object>>of(Map.of("amount", 100))));
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                if (calls.incrementAndGet() == 1) {
                    assertThat(prompt).contains("Synthesize one adaptive business analysis prompt contract", "客户交易", "成交金额")
                        .doesNotContain("\"amount\":100");
                    return com.chatchat.agents.orchestration.analysis.prompt.AdaptiveBusinessAnalysisPromptSynthesizerTest.response();
                }
                assertThat(prompt).contains("Adaptive business analysis instruction", "客户经营分析师", "CONTRIBUTION", "\"amount\":100",
                    "supportingValues must cite every raw input value",
                    "Emit at least one material evidence-bound finding for every non-empty question-relevant dataset",
                    "Never describe a returned transaction, holding, profit/loss or position dataset as missing");
                return "{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"limitations\":[\"bounded\"]}";
            }
        };
        var metadata = new LinkedHashMap<String, Object>();
        new UnifiedQuestionAnalysisGraph().execute("识别活跃度变化", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> { });
        assertThat(calls.get()).isEqualTo(2);
        assertThat(metadata).containsEntry("adaptiveAnalysisPromptMode", "MODEL_SYNTHESIZED")
            .containsEntry("adaptiveAnalysisPromptModelCalls", 1)
            .containsEntry("unifiedAnalysisModelCalls", 1);
        assertThat(metadata.get("unifiedAnalysisGraphNodes").toString()).contains("prompt_synthesis");
    }

    @Test void mediumStructuredResultUsesProgressiveEvidenceAndStaysWithinTokenBudget() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(index ->
            Map.<String, Object>of("fund_code", "fund-" + index,
                "current_scale", index * 1000.25, "payload", "x".repeat(500))).toList();
        var datasets = List.of(new Dataset("market", Map.of("runtimeAnalysisInputs", Map.of(
            "verifiedCalculations", List.of(Map.of("metric", "total", "value", 5_000_000)))), rows));
        var metadata = new LinkedHashMap<String, Object>();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                assertThat(new com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator()
                    .estimate(prompt).tokens()).isLessThanOrEqualTo(12_000);
                assertThat(prompt).contains("FULL_SCAN_PROFILE_WITH_SELECTED_RECORDS", "verifiedCalculations")
                    .doesNotContain("fund-50");
                return "{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"limitations\":[\"bounded\"]}";
            }
        };

        new UnifiedQuestionAnalysisGraph().execute("analyze", datasets, () -> datasets, model, scope,
            new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});

        assertThat(metadata).containsEntry("unifiedEvidenceMode", "BOUNDED_PROJECTION");
        assertThat(((Number) metadata.get("unifiedAnalysisMaxPromptTokens")).longValue()).isLessThanOrEqualTo(12_000);
    }

    @Test void rejectsForeignDatasetEvenWhenEvidenceIsProjected() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) { calls.incrementAndGet(); return product("foreign"); }
        };
        var datasets = List.of(new Dataset("dataset1", Map.of(), List.<Map<String, Object>>of(Map.of("VALUE", 1))));
        assertThatThrownBy(() -> new UnifiedQuestionAnalysisGraph().execute("question", datasets,
            () -> datasets, model, scope, new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(),
            new LinkedHashMap<>(), () -> {})).hasMessageContaining("unbound dataset");
        var large = List.of(new Dataset("large", Map.of(), List.<Map<String, Object>>of(Map.of("text", "x".repeat(170_000)))));
        assertThatThrownBy(() -> new UnifiedQuestionAnalysisGraph().execute("question", large,
            () -> large, model, scope, new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(),
            new LinkedHashMap<>(), () -> {})).hasMessageContaining("unbound dataset");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void fiftyThousandRowsUseFullScanAndBoundedDrillDownWithOriginalReferences() {
        List<Dataset> datasets = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(dataset ->
            new Dataset("dataset" + dataset, Map.of(), java.util.stream.IntStream.rangeClosed(1, 10_000)
                .mapToObj(row -> Map.<String, Object>of("VALUE", row, "label", "label-" + row)).toList())).toList();
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                assertThat(prompt.length()).isLessThan(160_000);
                if (calls.incrementAndGet() == 1) {
                    assertThat(prompt).contains("FULL_SCAN_PROFILE_WITH_SELECTED_RECORDS", "50005000")
                        .doesNotContain("label-5001");
                    return ModelProtocolJson.compact(Map.of("schemaVersion", "unified_question_analysis.v1",
                        "findings", List.of(), "evidenceRequests", List.of(Map.of("operation", "READ_RECORDS",
                            "datasetReference", "dataset3", "fromRecord", 5001, "limit", 1))));
                }
                assertThat(prompt).contains("dataset3.records[5001]", "label-5001");
                return product("dataset3").replace("records[1]", "records[5001]")
                    .replace("value is 1", "value is 5001").replace("\"1\"", "\"5001\"");
            }
        };
        var metadata = new LinkedHashMap<String, Object>();
        var outcomes = new UnifiedQuestionAnalysisGraph().execute("Investigate dataset3 record 5001", datasets,
            () -> datasets, model, scope, new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(outcomes).hasSize(5);
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 2)
            .containsEntry("unifiedEvidenceMode", "BOUNDED_PROJECTION")
            .containsEntry("unifiedAnalysisStatus", "COMPLETED_WITH_LIMITATIONS");
        @SuppressWarnings("unchecked") var coverage = (List<Map<String, Object>>) metadata.get("unifiedEvidenceScanCoverage");
        assertThat(coverage).hasSize(5).allSatisfy(item -> assertThat(item)
            .containsEntry("scannedRecords", 10_000).containsEntry("chunkCount", 10).containsEntry("scanComplete", true));
        assertThat(outcomes.get("dataset3").summary().datasetSummary().evidence().toString()).contains("dataset3.records[5001]");
    }

    @Test void supplementaryEvidenceIsBoundedToOneFollowUpModelRound() {
        List<Dataset> datasets = List.of(new Dataset(
            "dataset1", Map.of(), List.of(Map.of("VALUE", 1), Map.of("VALUE", 2))));
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                calls.incrementAndGet();
                return ModelProtocolJson.compact(Map.of(
                    "schemaVersion", "unified_question_analysis.v1",
                    "findings", List.of(Map.of(
                        "datasetReference", "dataset1",
                        "claimClass", "OBSERVED_RETURNED_FACT",
                        "claim", "A returned value is 1",
                        "operation", "OBSERVE",
                        "recordRefs", List.of("dataset1.records[1]"),
                        "supportingValues", List.of("\"VALUE\":1"),
                        "confidence", "HIGH")),
                    "evidenceRequests", List.of(Map.of(
                        "operation", "READ_RECORDS", "datasetReference", "dataset1",
                        "fromRecord", 1, "limit", 1))));
            }
        };

        new UnifiedQuestionAnalysisGraph().execute("question", datasets, () -> datasets, model,
            scope, new AnalysisNodeProtocol(), AnalysisEvidenceSpillStore.disabled(),
            new LinkedHashMap<>(), () -> {});

        assertThat(calls.get()).isEqualTo(2);
    }

    private static String product(String dataset) {
        var finding = new LinkedHashMap<String, Object>();
        finding.put("datasetReference", dataset);
        finding.put("claimClass", "OBSERVED_RETURNED_FACT");
        finding.put("claim", "Returned value is 1");
        finding.put("significance", "Answers the current observation question");
        finding.put("operation", "OBSERVE");
        finding.put("recordRefs", List.of(dataset + ".records[1]"));
        finding.put("supportingValues", List.of("1"));
        finding.put("confidence", "HIGH");
        finding.put("caveats", List.of());
        return ModelProtocolJson.compact(Map.of("schemaVersion", "unified_question_analysis.v1",
            "findings", List.of(finding), "limitations", List.of()));
    }
}
