package com.chatchat.mcpserver.ops;

import io.modelcontextprotocol.server.McpSyncServer;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpRequirementAnalysisMcpToolPublisherTest {

    @Test
    void publishesRequirementAnalysisAsAnHttpSpecificContract() {
        McpSyncServer server = mock(McpSyncServer.class);
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            server, mock(CommandTemplateDiscoveryService.class));

        publisher.refresh();

        verify(server).removeTool(HttpRequirementAnalysisMcpToolPublisher.TOOL_NAME);
        ArgumentCaptor<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> specification =
            ArgumentCaptor.forClass(io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
        verify(server).addTool(specification.capture());
        assertThat(specification.getValue().tool().inputSchema().properties())
            .containsKeys("query", "requirements");
        assertThat(specification.getValue().tool().inputSchema().required()).isEmpty();
        verify(server).notifyToolsListChanged();
    }

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

    @Test
    void acceptsPlannerIntentShapeAndGeneratesCanonicalRequirementFields() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of("templateId", "http_query_yarn_nodes"))
        ));
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of(
            "requirements", List.of(Map.of(
                "intent", "分析 CDH YARN 节点内存和 vcores",
                "requiredOutputs", List.of("节点内存", "vcores"),
                "constraints", List.of("数据来自 ResourceManager API")
            )),
            "context", Map.of("env", "PROD", "assetType", "http_endpoint")
        ));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", "分析 CDH YARN 节点内存和 vcores");
        Map<?, ?> coverage = (Map<?, ?>) ((List<?>) result.get("coverage")).get(0);
        Map<?, ?> requirement = (Map<?, ?>) coverage.get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo("分析 CDH YARN 节点内存和 vcores");
        org.mockito.Mockito.verify(discovery).query(argThat(query -> {
            Map<?, ?> filters = (Map<?, ?>) query.get("filters");
            return "PROD".equals(filters.get("env"))
                && filters.toString().contains("ResourceManager API");
        }));
    }

    @Test
    void acceptsQueryShorthandProducedByRuntimePlanRepair() {
        CommandTemplateDiscoveryService discovery = mock(CommandTemplateDiscoveryService.class);
        when(discovery.query(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of(
            "returnedCount", 1,
            "templates", List.of(Map.of("templateId", "dynamic_http_template"))
        ));
        HttpRequirementAnalysisMcpToolPublisher publisher = new HttpRequirementAnalysisMcpToolPublisher(
            mock(McpSyncServer.class), discovery);

        Map<String, Object> result = publisher.analyze(Map.of("query", "inspect requested HTTP resource"));

        assertThat(result).containsEntry("success", true)
            .containsEntry("goal", "inspect requested HTTP resource")
            .containsEntry("requirementCount", 1);
        Map<?, ?> requirement = (Map<?, ?>) ((Map<?, ?>) ((List<?>) result.get("coverage")).get(0))
            .get("requirement");
        assertThat(requirement.get("id")).isEqualTo("requirement_1");
        assertThat(requirement.get("description")).isEqualTo("inspect requested HTTP resource");
    }
}
