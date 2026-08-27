package com.chatchat.mcpserver.api.publication;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionApiRequirementAnalysisProtocolE2E {

    @Test
    void plannerIntentAliasCrossesNormalizationAndApiDiscovery() {
        AtomicReference<Map<String, Object>> capturedQuery = new AtomicReference<>();
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenAnswer(invocation -> {
            capturedQuery.set(Map.copyOf(invocation.getArgument(0)));
            return Map.of(
                "returnedCount", "1",
                "templates", List.of(Map.of("templateId", "generated-" + System.nanoTime())),
                "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1"));
        });
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);
        String dynamicIntent = "分析动态指标-" + System.nanoTime();

        Map<String, Object> result = publisher.analyze(Map.of(
            "query", "  " + dynamicIntent + "  ",
            "context", Map.of("environment", "PRE", "assetName", "asset-" + System.nanoTime()),
            "limitPerRequirement", 0));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", dynamicIntent)
            .containsEntry("executionTool", "api_template_execute");
        Map<?, ?> requirement = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("coverage")).get(0))
            .get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo(dynamicIntent);
        assertThat(capturedQuery.get()).containsEntry("limit", 1);
        Map<?, ?> filters = (Map<?, ?>) capturedQuery.get().get("filters");
        assertThat(filters.get("environment")).isEqualTo("PRE");
        assertThat(filters.get("intent")).isEqualTo(dynamicIntent);
    }
}
