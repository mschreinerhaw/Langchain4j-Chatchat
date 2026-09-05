package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedAnalysisEvidenceTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("t", "u", "r", "q", "c");

    @Test void finalFindingCheckpointAlsoDependsOnRecordsOutsideTheModelView() {
        var rows = rows();
        var store = store(new ConcurrentHashMap<>());
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"schemaVersion\":\"unified_question_analysis.v1\",\"findings\":[],\"limitations\":[\"No supported conclusion\"]}");
        var graph = new UnifiedQuestionAnalysisGraph();
        var protocol = new com.chatchat.agents.orchestration.analysis.nodes.analysis.AnalysisNodeProtocol();
        var metadata = new LinkedHashMap<String, Object>();
        var datasets = List.of(new Dataset("d", Map.of(), rows));
        graph.execute("question", datasets, () -> datasets, model, scope, protocol, store, metadata, () -> {});
        graph.execute("question", datasets, () -> datasets, model, scope, protocol, store, metadata, () -> {});
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 0).containsEntry("unifiedAnalysisRestored", true);
        rows.set(501, Map.of("value", 1, "padding", "changed-but-not-selected"));
        var changed = List.of(new Dataset("d", Map.of(), rows));
        graph.execute("question", changed, () -> changed, model, scope, protocol, store, metadata, () -> {});
        assertThat(metadata).containsEntry("unifiedAnalysisModelCalls", 1).containsEntry("unifiedAnalysisRestored", false);
        verify(model, times(2)).chat(anyString());
    }

    @Test void semanticContextCanBeReadInPagesWithoutInventingCalculations() {
        var datasets = List.of(new Dataset("d", Map.of("runtimeAnalysisInputs", Map.of(
            "verifiedCalculations", List.of(Map.of("value", 2001), Map.of("value", 42)))), rows()));
        var engine = new BoundedAnalysisEvidence();
        var prepared = engine.prepare(datasets, AnalysisEvidenceSpillStore.disabled(), scope, new LinkedHashMap<>(), () -> {});
        var read = engine.read(prepared, List.of(Map.of("operation", "READ_CONTEXT", "datasetReference", "d",
            "path", List.of("runtimeAnalysisInputs", "verifiedCalculations"), "fromItem", 1, "limit", 1)), () -> {});
        assertThat(read.toString()).contains("value=42", "totalItems=2").doesNotContain("value=2001");
    }

    @Test void partitionsMergeExactSumsAndCountsAndReuseOnlyUnchangedCheckpoints() {
        var store = store(new ConcurrentHashMap<>());
        var rows = rows();
        var engine = new BoundedAnalysisEvidence();
        var metadata = new LinkedHashMap<String, Object>();
        var first = engine.prepare(List.of(new Dataset("d", Map.of(), rows)), store, scope, metadata, () -> {});
        assertThat(first.projected()).isTrue();
        assertThat(first.views().toString()).contains("sum=2001", "numericCount=1001", "minRecord=1", "maxRecord=1001");
        var second = engine.prepare(List.of(new Dataset("d", Map.of(), rows)), store, scope, metadata, () -> {});
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(metadata.get("unifiedEvidenceScanCoverage").toString()).contains("restoredChunks=2");
        rows.set(501, Map.of("value", 1, "padding", "changed-but-not-selected"));
        var third = engine.prepare(List.of(new Dataset("d", Map.of(), rows)), store, scope, metadata, () -> {});
        assertThat(third.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(metadata.get("unifiedEvidenceScanCoverage").toString()).contains("restoredChunks=1");
        var read = engine.read(third, List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "d",
            "fromRecord", 502, "limit", 1)), () -> {});
        assertThat(read.toString()).contains("d.records[502]", "changed-but-not-selected");
        assertThatThrownBy(() -> engine.read(third, List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "other",
            "fromRecord", 1, "limit", 1)), () -> {})).hasMessageContaining("unbound");
        assertThatThrownBy(() -> engine.read(third, List.of(Map.of("operation", "READ_RECORDS", "datasetReference", "d",
            "fromRecord", 1, "limit", 10000)), () -> {})).hasMessageContaining("range");
    }

    @Test void failedPartitionDoesNotEraseSuccessfulCheckpoints() {
        Map<String, String> cache = new ConcurrentHashMap<>();
        var store = store(cache);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (key.endsWith(":1000") && fail.getAndSet(false)) throw new IllegalStateException("partition failure");
            cache.put(key + invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(store).checkpoint(any(), anyString(), anyString(), anyString());
        var datasets = List.of(new Dataset("d", Map.of(), rows()));
        var engine = new BoundedAnalysisEvidence();
        assertThatThrownBy(() -> engine.prepare(datasets, store, scope, new LinkedHashMap<>(), () -> {}))
            .hasMessageContaining("partition failure");
        var metadata = new LinkedHashMap<String, Object>();
        engine.prepare(datasets, store, scope, metadata, () -> {});
        assertThat(metadata.get("unifiedEvidenceScanCoverage").toString()).contains("restoredChunks=1", "scanComplete=true");
    }

    private List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) rows.add(Map.of("value", i == 1000 ? 1001 : 1, "padding", "x".repeat(100)));
        return rows;
    }
    private AnalysisEvidenceSpillStore store(Map<String, String> cache) {
        var store = mock(AnalysisEvidenceSpillStore.class);
        when(store.readCheckpoint(any(), anyString(), anyString())).thenAnswer(invocation ->
            Optional.ofNullable(cache.get(invocation.getArgument(1, String.class) + invocation.getArgument(2, String.class))));
        doAnswer(invocation -> {
            cache.put(invocation.getArgument(1, String.class) + invocation.getArgument(2, String.class), invocation.getArgument(3));
            return null;
        }).when(store).checkpoint(any(), anyString(), anyString(), anyString());
        return store;
    }
}
