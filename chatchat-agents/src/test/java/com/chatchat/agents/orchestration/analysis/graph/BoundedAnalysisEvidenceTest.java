package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dataset.PagedDatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedAnalysisEvidenceTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("t", "u", "r", "q", "c");

    @Test void nestedReadPreservesParentEvidenceIdentity() {
        var datasets = List.of(new Dataset("search", Map.of(), List.of(Map.of("data",
            Map.of("rows", List.of(Map.of("value", 42), Map.of("value", 17)))))));
        var engine = new BoundedAnalysisEvidence();
        var prepared = engine.prepare(datasets, AnalysisEvidenceSpillStore.disabled(), scope, new LinkedHashMap<>(), () -> {});
        assertThat(prepared.views().toString()).contains("nestedCollections", "itemCount=2");
        var read = engine.read(prepared, List.of(Map.of("operation", "READ_NESTED_RECORDS",
            "datasetReference", "search", "record", 1, "path", List.of("data", "rows"), "fromItem", 1, "limit", 1)), () -> {});
        assertThat(read.toString()).contains("search.records[1]", "value=17", "availableItemCount=2")
            .doesNotContain("value=42");
    }

    @Test void manyLogicalDatasetsRemainBoundAndReadableWithinOnePromptBudget() {
        List<Dataset> datasets = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            datasets.add(new Dataset("dataset-" + i,
                Map.of("schema", "x".repeat(2_000)),
                List.of(Map.of("value", i, "description", "y".repeat(1_000)))));
        }
        var engine = new BoundedAnalysisEvidence();
        var metadata = new LinkedHashMap<String, Object>();
        var prepared = engine.prepare(datasets, AnalysisEvidenceSpillStore.disabled(), scope,
            metadata, () -> {});

        assertThat(prepared.projected()).isTrue();
        assertThat(prepared.sources()).hasSize(24).containsKeys("dataset-0", "dataset-23");
        assertThat(prepared.views()).extracting(view -> view.get("datasetReference"))
            .contains("dataset-0", "dataset-23");
        assertThat(ModelProtocolJson.compact(prepared.views()).length())
            .isLessThanOrEqualTo(BoundedAnalysisEvidence.INPUT_BUDGET);
        assertThat(metadata).containsEntry("unifiedEvidenceCatalogDatasetCount", 24);
        assertThat(engine.read(prepared, List.of(Map.of("operation", "READ_RECORDS",
            "datasetReference", "dataset-23", "fromRecord", 1, "limit", 1)), () -> {}).toString())
            .contains("dataset-23.records[1]", "value=23");
    }

    @Test void profilesAndReadsPagedHandleWithoutWholeDatasetMaterialization() {
        List<Map<String, Object>> source = new ArrayList<>();
        for (int i = 0; i < 2_501; i++) source.add(Map.of("value", i));
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger largestRequestedPage = new AtomicInteger();
        var handle = new PagedDatasetHandle(source.size(), true, (offset, limit) -> {
            reads.incrementAndGet();
            largestRequestedPage.accumulateAndGet(limit, Math::max);
            int from = Math.toIntExact(offset);
            if (from >= source.size()) return new DatasetHandle.Page(offset, List.of(), false);
            int to = Math.min(source.size(), from + limit);
            return new DatasetHandle.Page(offset, source.subList(from, to), to < source.size());
        }, request -> new DatasetHandle.OperationResult(
            Map.of("value", 3_126_250, "operation", request.operation()),
            Map.of("recordCount", source.size(), "scope", "all returned rows")),
            null, Map.of("provider", "test-cursor"));
        var engine = new BoundedAnalysisEvidence();
        var metadata = new LinkedHashMap<String, Object>();
        var prepared = engine.prepare(List.of(new Dataset("paged", Map.of(), handle)),
            store(new ConcurrentHashMap<>()), scope, metadata, () -> {});
        assertThat(prepared.projected()).isTrue();
        assertThat(prepared.views().toString()).contains("recordCount=2501");
        assertThat(metadata.get("unifiedEvidenceScanCoverage").toString()).contains("scanComplete=true");
        var page = engine.read(prepared, List.of(Map.of("operation", "READ_RECORDS",
            "datasetReference", "paged", "fromRecord", 2401, "limit", 100)), () -> {});
        assertThat(page.toString()).contains("paged.records[2401]", "value=2400");
        var pushedDown = engine.read(prepared, List.of(Map.of("operation", "EXECUTE_OPERATION",
            "datasetReference", "paged", "analysisOperation", "AGGREGATE",
            "specification", Map.of("metric", "value", "aggregation", "SUM"))), () -> {});
        assertThat(pushedDown.toString()).contains("AGGREGATE", "3126250", "recordCount=2501");
        assertThat(reads).hasValueGreaterThanOrEqualTo(4);
        assertThat(largestRequestedPage).hasValueLessThanOrEqualTo(1_000);
    }

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
