package com.chatchat.mcpserver.ops;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpRequirementAnalysisMcpToolPublisherTest {

    @Test
    void analyzesHttpRequirementsThroughTheHttpTemplateDomain() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(argThat(query -> "http_endpoint".equals(query.get("assetType"))
            && "http".equals(query.get("finalDecision")))))
            .thenReturn(Map.of(
                "returnedCount", 1,
                "templates", List.of(Map.of(
                    "templateId", "http_customer_profile",
                    "capabilitySpec", Map.of("capabilities", List.of("customer_profile")),
                    "outputSchema", Map.of("type", "object")
                )),
                "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1")
            ));
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "goal", "analyze customer credit",
            "requirements", List.of(Map.of(
                "id", "customer_profile",
                "description", "query customer profile",
                "requiredOutputs", List.of("customerId")
            ))
        ));

        assertThat(result).containsEntry("success", true)
            .containsEntry("allRequirementsHaveCandidates", true)
            .containsEntry("executionTool", "http_request_execute");
        assertThat(result.toString()).contains("CANDIDATES_FOUND", "http_customer_profile")
            .contains("not semantic acceptance");
    }

    @Test
    void reportsHttpRequirementGap() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
            "returnedCount", 0,
            "templates", List.of(),
            "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1")
        ));
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "goal", "analyze customer credit",
            "requirements", List.of(Map.of("id", "credit_score", "description", "query credit score"))
        ));

        assertThat(result).containsEntry("allRequirementsHaveCandidates", false);
        assertThat(result.get("missingRequirementIds")).isEqualTo(List.of("credit_score"));
    }
}
