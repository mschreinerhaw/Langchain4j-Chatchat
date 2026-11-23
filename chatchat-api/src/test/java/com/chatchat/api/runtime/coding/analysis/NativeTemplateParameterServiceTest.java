package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeTemplateParameterServiceTest {
    @Test void deliversTheWholeCatalogAsTypeGroupedFilesInOneNativeTask() throws Exception {
        var coding = mock(NativeCodingTasks.class);
        var assets = mock(UserAssetService.class);
        var json = new ObjectMapper();
        var service = new NativeTemplateParameterService(coding, assets, new CodingInvocationContext(), json, "");
        var scope = new KernelDataScope("tenant", "user", "request", "conversation", "run", null, Map.of());
        var captured = new LinkedHashMap<String, byte[]>();
        when(coding.snapshot(eq(scope), anyString())).thenReturn(Optional.empty());
        when(coding.taskIdentity(eq(scope), anyString())).thenReturn("task");
        when(assets.store(any(), any(), eq("task"), eq("run"), anyString(), anyString(), anyString(), any(byte[].class)))
            .thenAnswer(call -> {
                String name = call.getArgument(5);
                byte[] bytes = call.getArgument(7);
                captured.put(name, bytes);
                return file(name, bytes);
            });
        when(coding.submit(eq(scope), anyString(), anyList(), anyString(), eq("model"), anyList(), anyMap()))
            .thenReturn(new NativeCodingTasks.Submitted("task", "run", null));
        byte[] selection = json.writeValueAsBytes(Map.of(
            "selectedAssetIds", List.of("asset-149"), "selectionStatus", "MATCHED"));
        when(coding.await(eq(scope), eq("task"), any())).thenReturn(Map.of(
            "status", "COMPLETED", "artifacts", List.of(file("asset-discovery.json", selection))));
        when(assets.readTask(scope, "task", "asset-discovery.json")).thenReturn(selection);
        var cards = IntStream.range(0, 150)
            .mapToObj(i -> Map.of("assetId", "asset-" + i, "type", i < 110 ? "ssh_host" : "sql_datasource",
                "name", i == 149 ? "LiveData\u6d4b\u8bd5\u5e93_223" : "Fixture " + i))
            .toList();

        assertThat(service.propose(scope, "model", 7, ClineDiscoveryPort.Stage.CONFIRM_ASSETS,
            Map.of("question", "Inspect LiveData\u6d4b\u8bd5\u5e93_223 InnoDB", "catalogVersion", "v1", "candidates", cards), () -> false))
            .containsEntry("selectedAssetIds", List.of("asset-149"));

        var manifest = json.readTree(captured.get("discovery-input.json"));
        assertThat(manifest.path("authorizedAssetCount").asInt()).isEqualTo(150);
        assertThat(manifest.has("candidates")).isFalse();
        var ids = new java.util.LinkedHashSet<String>();
        for (var part : manifest.path("catalogFiles")) {
            byte[] bytes = captured.get(part.path("fileName").asText());
            assertThat(UserAssetService.hash(bytes)).isEqualTo(part.path("sha256").asText());
            var rows = json.readTree(bytes);
            assertThat(rows.size()).isEqualTo(part.path("count").asInt());
            for (var row : rows) {
                assertThat(row.path("type").asText()).isEqualTo(part.path("assetType").asText());
                assertThat(ids.add(row.path("assetId").asText())).isTrue();
                if ("asset-149".equals(row.path("assetId").asText()))
                    assertThat(row.path("name").asText()).isEqualTo("LiveData\u6d4b\u8bd5\u5e93_223");
            }
        }
        assertThat(ids).hasSize(150).contains("asset-149");
        verify(coding).submit(eq(scope), anyString(), argThat(files -> files.size() == 6),
            anyString(), eq("model"), anyList(), anyMap());
    }

    private static UserAssetService.File file(String name, byte[] bytes) {
        return new UserAssetService.File(name, "CONTEXT", name, "application/json",
            UserAssetService.hash(bytes), bytes.length, "READY", "task", "run", name, 0);
    }
}
