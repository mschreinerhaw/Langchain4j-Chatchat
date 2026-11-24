package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.PagedDatasetHandle;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.api.runtime.coding.nativecline.NativeTaskCapabilities;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeAnalysisBridgeTest {
    @Test void freezesTenThousandRowsAndAllTwentySourcesIntoTheSameTaskInventory() throws Exception {
        var assets = mock(UserAssetService.class);
        var coding = mock(NativeCodingTasks.class);
        var capabilities = mock(NativeTaskCapabilities.class);
        var json = new ObjectMapper();
        var bridge = new NativeAnalysisBridge(coding, assets, new CodingInvocationContext(), json,
            new CodingAnalysisProperties());
        ReflectionTestUtils.setField(bridge, "capabilities", capabilities);
        var scope = new KernelDataScope("tenant", "user", "request", "conversation", "run", null, Map.of());
        var context = new AnalysisCoverageCoordinator.Request(null, "Analyze", null, Map.of(),
            Map.of("modelName", "test-model"), null, null, null, null);
        var offsets = new ArrayList<Long>();
        var pageable = new PagedDatasetHandle(10_000, true, (offset, limit) -> {
            offsets.add(offset);
            long end = Math.min(10_000, offset + Math.min(limit, 1_000));
            var rows = LongStream.range(offset, end).mapToObj(i -> i == 9_999
                ? Map.<String, Object>of("n", i, "lateField", "row value stays in snapshot")
                : Map.<String, Object>of("n", i)).toList();
            return new DatasetHandle.Page(offset, rows, end < 10_000);
        }, null, null, Map.of());
        var sources = new LinkedHashMap<String, Dataset>();
        sources.put("range", new Dataset("range", Map.of("sourceComplete", true), pageable));
        for (int i = 1; i < 20; i++) {
            String id = "source-" + i;
            var row = new LinkedHashMap<String, Object>();
            row.put("n", i);
            if (i == 1) {
                for (int field = 0; field < 20; field++) row.put("field" + field, field);
            }
            sources.put(id, new Dataset(id, Map.of(), List.of(row)));
        }
        var transferred = new LinkedHashMap<String, byte[]>();
        when(assets.storeSnapshot(any(), anyString(), anyString(), anyString(), anyString(),
            any(Path.class), anyLong(), anyString())).thenAnswer(call -> {
                String name = call.getArgument(4);
                byte[] bytes = Files.readAllBytes(call.<Path>getArgument(5));
                assertThat(bytes).hasSize(Math.toIntExact(call.getArgument(6)));
                assertThat(UserAssetService.hash(bytes)).isEqualTo(call.getArgument(7));
                transferred.put(name, bytes);
                return file(name, bytes);
            });
        when(assets.store(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), any(byte[].class)))
            .thenAnswer(call -> {
                String name = call.getArgument(5);
                byte[] bytes = call.getArgument(7);
                transferred.put(name, bytes);
                return file(name, bytes);
            });
        when(coding.taskIdentity(scope, "request")).thenReturn("task");
        when(capabilities.capture(eq(scope), eq("test-model"), anyMap(), anyMap(), eq(20), eq(10_019L)))
            .thenReturn(Map.of());
        when(coding.submit(eq(scope), eq("request"), anyList(), anyString(), eq("test-model"),
            anyList(), anyMap(), anyMap())).thenAnswer(call -> {
                assertThat(call.<List<String>>getArgument(2)).hasSize(41);
                assertThat(json.valueToTree(call.<Map<String, Object>>getArgument(6))
                    .path("properties").path("analysisStatus").path("enum").toString())
                    .isEqualTo("[\"PARTIAL\",\"INPUT_REQUIRED\"]");
                return new NativeCodingTasks.Submitted("task", "run", null);
            });
        when(coding.await(eq(scope), eq("task"), any(), any())).thenReturn(new LinkedHashMap<>());

        var result = bridge.execute(context, scope, "request", "Analyze", CodingAnalysisService.primaryReportSchema(), sources, ignored -> {}, false);

        assertThat(offsets).containsExactlyElementsOf(LongStream.range(0, 10).map(i -> i * 1000).boxed().toList());
        var manifest = json.readTree(transferred.get("data-supply-manifest.json"));
        assertThat(manifest.path("datasets").size()).isEqualTo(20);
        assertThat(manifest.path("datasets").get(0).path("inputStatus").asText()).isEqualTo("DELIVERED_SNAPSHOT");
        assertThat(manifest.path("datasets").get(1).path("inputStatus").asText()).isEqualTo("SOURCE_UNKNOWN");
        assertThat(manifest.path("datasets").get(0).path("rowCount").asLong()).isEqualTo(10_000);
        assertThat(manifest.path("datasets").get(0).path("countAssurance").asText()).isEqualTo("VERIFIED_TOTAL");
        assertThat(manifest.path("datasets").get(0).path("observedFields").toString()).isEqualTo("[\"lateField\",\"n\"]");
        assertThat(manifest.path("datasets").get(0).path("fieldInventoryComplete").asBoolean()).isTrue();
        assertThat(manifest.path("datasets").get(1).path("fieldInventoryComplete").asBoolean()).isFalse();
        assertThat(manifest.path("datasets").get(1).path("observedFields").size()).isEqualTo(16);
        assertThat(manifest.toString()).doesNotContain("row value stays in snapshot");
        assertThat(new String(transferred.get("source-1.jsonl"), java.nio.charset.StandardCharsets.UTF_8).lines().count())
            .isEqualTo(10_000);
        assertThat(result).containsEntry("deliveredRows", 10_019L);
    }

    @Test void validatesUnknownTotalsAndRejectsNonProgressOrUnreliableDeclaredTotals() {
        var unknown = new PagedDatasetHandle(0, false, (offset, limit) ->
            new DatasetHandle.Page(offset, List.of(Map.of("n", 1)), false), null, null, Map.of());
        assertThat(unknown.readVerifiedPage(0, 1000, -1).hasMore()).isFalse();
        assertThatThrownBy(() -> unknown.readVerifiedPage(0, 1000, 2))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("declared row count");
        var emptyContinuation = new PagedDatasetHandle(0, false, (offset, limit) ->
            new DatasetHandle.Page(offset, List.of(), true), null, null, Map.of());
        assertThatThrownBy(() -> emptyContinuation.readVerifiedPage(0, 1000, -1))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-progressing");
    }

    private UserAssetService.File file(String name, byte[] bytes) {
        return new UserAssetService.File(name, "DATASET", name, "application/json",
            UserAssetService.hash(bytes), bytes.length, "READY", "task", "run", name, 0);
    }
}
