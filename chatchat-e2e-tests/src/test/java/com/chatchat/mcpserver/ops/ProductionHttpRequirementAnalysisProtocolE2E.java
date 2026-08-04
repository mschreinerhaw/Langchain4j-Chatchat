package com.chatchat.mcpserver.ops;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionHttpRequirementAnalysisProtocolE2E {

    @Test
    void plannerIntentAliasCrossesNormalizationAndHttpDiscovery() {
        AtomicReference<Map<String, Object>> capturedQuery = new AtomicReference<>();
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(any())).thenAnswer(invocation -> {
            capturedQuery.set(Map.copyOf(invocation.getArgument(0)));
            return Map.of(
                "returnedCount", 1,
                "templates", List.of(Map.of("templateId", "generated-" + System.nanoTime())),
                "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1"));
        });
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);
        String dynamicIntent = "inspect-dynamic-resource-" + System.nanoTime();

        Map<String, Object> result = publisher.analyze(Map.of(
            "requirements", List.of(Map.of(
                "intent", dynamicIntent,
                "requiredOutputs", List.of("容量", "usage"),
                "constraints", List.of("read-only"))),
            "context", Map.of("env", "DEV", "service", "service-" + System.nanoTime()),
            "limitPerRequirement", "not-a-number"));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", dynamicIntent)
            .containsEntry("executionTool", "http_request_execute");
        Map<?, ?> requirement = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("coverage")).get(0))
            .get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo(dynamicIntent);
        assertThat(capturedQuery.get()).containsEntry("limit", 5)
            .containsEntry("assetType", "http_endpoint")
            .containsEntry("finalDecision", "http");
        Map<?, ?> filters = (Map<?, ?>) capturedQuery.get().get("filters");
        assertThat(filters.get("env")).isEqualTo("DEV");
        assertThat(filters.get("intent")).isEqualTo(dynamicIntent);
    }
}
