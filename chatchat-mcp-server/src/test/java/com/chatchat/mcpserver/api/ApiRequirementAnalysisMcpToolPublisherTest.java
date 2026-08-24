package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRequirementAnalysisMcpToolPublisherTest {

    @Test
    void refreshKeepsRequirementAnalysisInternalToTheApiBridge() {
        McpSyncServer server = mock(McpSyncServer.class);
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            server, mock(ApiTemplateDiscoveryMcpToolPublisher.class));

        publisher.refresh();

        verify(server).removeTool(ApiRequirementAnalysisMcpToolPublisher.TOOL_NAME);
        verify(server, never()).addTool(any());
        verify(server, never()).notifyToolsListChanged();
    }

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

    @Test
    void acceptsGenericPlannerIntentShapeWithoutDomainHardcoding() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of("templateId", "matched_api_template"))
        ));
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "requirements", List.of(Map.of(
                "intent", "retrieve requested business metrics",
                "requiredOutputs", List.of("metricA", "metricB"),
                "constraints", List.of("read only")
            )),
            "context", Map.of("env", "PROD", "service", "analytics")
        ));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", "retrieve requested business metrics");
        Map<?, ?> coverage = (Map<?, ?>) ((List<?>) result.get("coverage")).get(0);
        Map<?, ?> requirement = (Map<?, ?>) coverage.get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo("retrieve requested business metrics");
        org.mockito.Mockito.verify(discovery).query(argThat(query -> {
            Map<?, ?> filters = (Map<?, ?>) query.get("filters");
            return "PROD".equals(filters.get("env"))
                && "analytics".equals(filters.get("service"))
                && filters.toString().contains("read only");
        }));
    }

    @Test
    void acceptsQueryShorthandProducedByRuntimePlanRepair() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of("templateId", "dynamic_api_template"))
        ));
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of("query", "inspect requested API data"));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", "inspect requested API data")
            .containsEntry("requirementCount", 1);
        Map<?, ?> requirement = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("coverage")).get(0))
            .get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo("inspect requested API data");
    }

    @Test
    void acceptsPlannerGoalAsSingleRequirementShorthand() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of("templateId", "matched_api_template"))
        ));
        ApiRequirementAnalysisMcpToolPublisher publisher = new ApiRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "goal", "retrieve all requested customer dimensions",
            "context", Map.of("env", "DEV")
        ));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", "retrieve all requested customer dimensions")
            .containsEntry("requirementCount", 1);
        Map<?, ?> requirement = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("coverage")).get(0))
            .get("requirement");
        assertThat(requirement.get("description"))
            .isEqualTo("retrieve all requested customer dimensions");
    }
}
