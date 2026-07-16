package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiRequirementAnalysisMcpToolPublisherTest {

    @Test
    void analyzesEveryRequirementWithoutClaimingSemanticAcceptance() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of(
                "templateId", "customer_profile_api",
                "capabilitySpec", Map.of("capabilities", List.of("customer_profile")),
                "outputSchema", Map.of("type", "object")
            )),
            "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1")
        ));
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
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
            .containsEntry("executionTool", "api_template_execute");
        assertThat(result.toString()).contains("CANDIDATES_FOUND", "customer_profile_api")
            .contains("not semantic acceptance");
    }

    @Test
    void reportsRequirementGapWhenNoCandidateExists() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenReturn(Map.of(
            "returnedCount", 0,
            "templates", List.of(),
            "selectionProtocol", Map.of("schemaVersion", "template_selection_protocol.v1")
        ));
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "goal", "analyze customer credit",
            "requirements", List.of(Map.of("id", "credit_score", "description", "calculate credit score"))
        ));

        assertThat(result).containsEntry("allRequirementsHaveCandidates", false);
        assertThat(result.get("missingRequirementIds")).isEqualTo(List.of("credit_score"));
        assertThat(result.toString()).contains("NO_CANDIDATE");
    }
}
