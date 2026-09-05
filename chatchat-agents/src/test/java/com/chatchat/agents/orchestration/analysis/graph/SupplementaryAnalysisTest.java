package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupplementaryAnalysisTest {
    @Test void graphRoutesTextRequestsToExtractionAndReturnsToGlobalFindings() {
        var datasets = List.of(new com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset(
            "logs", Map.of(), List.of(Map.of("text", "connection refused\n" + "x".repeat(9000)))));
        var model = mock(ChatModel.class);
        var globalCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(model.chat(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            if (prompt.startsWith("text_partition_extraction")) return "{\"events\":[]}";
            if (globalCalls.incrementAndGet() == 1) return "{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"evidenceRequests\":[{\"operation\":\"EXTRACT_TEXT\",\"datasetReference\":\"logs\",\"record\":1,\"field\":\"text\"}]}";
            assertThat(prompt).contains("EXTRACT_TEXT", "sourceComplete");
            return "{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"limitations\":[\"No supported diagnosis\"]}";
        });
        var metadata = new LinkedHashMap<String, Object>();
        new UnifiedQuestionAnalysisGraph().execute("diagnose", datasets, () -> datasets, model,
            GovernanceIsolationScope.runtime("t", "u", "r", "q", "c"),
            new com.chatchat.agents.orchestration.analysis.nodes.analysis.AnalysisNodeProtocol(),
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(globalCalls.get()).isEqualTo(2);
        assertThat(((Number) metadata.get("textExtractionModelCalls")).intValue()).isGreaterThan(0);
    }
    @Test void formulaUsesRuntimeValuesAndDoesNotSelfAuthorizeBusinessMeaning() {
        var context = Map.<String, Object>of("runtimeAnalysisInputs", Map.of("verifiedCalculations", List.of(Map.of(
            "status", "executed", "findings", List.of(Map.of("id", "current", "value", 120), Map.of("id", "baseline", "value", 100))))));
        var executor = new SupplementaryFormulaExecutor();
        var request = Map.<String, Object>of("expression", "(a-b)/b", "inputs", Map.of("a", "current", "b", "baseline"));
        var result = executor.execute(context, request);
        assertThat(result.get("value").toString()).isEqualTo("0.2");
        assertThat(result).containsEntry("conclusionEligible", false).containsEntry("status", "COMPUTED_REQUIRES_SEMANTIC_REVIEW");
        assertThatThrownBy(() -> executor.execute(context, Map.of("expression", "a", "inputs", Map.of("a", "invented"))))
            .hasMessageContaining("Unknown");
        assertThatThrownBy(() -> executor.execute(context, Map.of("expression", "a/0", "inputs", Map.of("a", "current"))))
            .hasMessageContaining("zero");
    }

    @Test void longTextPartitionsQuoteOriginalOffsetsAndReuseCheckpoints() {
        String text = "x".repeat(4000) + "\nconnection refused\n" + "y".repeat(4500);
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(call -> {
            String prompt = call.getArgument(0);
            assertThat(prompt.length()).isLessThan(5000);
            return prompt.contains("connection refused")
                ? "{\"events\":[{\"label\":\"connection error\",\"quote\":\"connection refused\"}]}"
                : "{\"events\":[]}";
        });
        Map<String, String> cached = new HashMap<>();
        var store = mock(AnalysisEvidenceSpillStore.class);
        when(store.readCheckpoint(any(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(cached.get(call.getArgument(1) + ":" + call.getArgument(2))));
        doAnswer(call -> { cached.put(call.getArgument(1) + ":" + call.getArgument(2), call.getArgument(3)); return null; })
            .when(store).checkpoint(any(), anyString(), anyString(), anyString());
        var scope = GovernanceIsolationScope.runtime("t", "u", "r", "q", "c");
        var extractor = new TextPartitionExtractor();
        var result = extractor.extract(text, "logs.records[1]", "text", "errors", 0, model, scope, store, () -> {});
        assertThat(result).containsEntry("sourceComplete", true);
        assertThat(result.get("events").toString()).contains("fromChar=4001", "connection refused", "logs.records[1]");
        var restored = extractor.extract(text, "logs.records[1]", "text", "errors", 0, model, scope, store, () -> {});
        assertThat(restored).containsEntry("modelCalls", 0);
        when(model.chat(anyString())).thenReturn("{\"events\":[{\"label\":\"error\",\"quote\":\"invented\"}]}");
        assertThatThrownBy(() -> extractor.extract("actual", "other", "text", "errors", 0, model, scope,
            AnalysisEvidenceSpillStore.disabled(), () -> {})).hasMessageContaining("ungrounded");
    }
}
