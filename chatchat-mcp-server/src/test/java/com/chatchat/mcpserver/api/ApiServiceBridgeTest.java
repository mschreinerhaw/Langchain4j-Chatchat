package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.templatepublication.TemplateQueryMcpToolPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiServiceBridgeTest {
    @Test
    void returnsAllCandidatesForModelReviewWithoutExecuting() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("templates", List.of(
            Map.of("templateId", "orders_v1", "title", "A", "relevanceScore", 1.0),
            Map.of("templateId", "orders_v2", "title", "B", "relevanceScore", 1.0))));

        ApiServiceBridge.Result result = new ApiServiceBridge(discovery).query(Map.of("query", "query an order"));

        assertThat(result.error()).isFalse();
        assertThat(result.body()).containsEntry("status", "CANDIDATES_FOUND")
            .containsEntry("requiresModelReview", true)
            .containsEntry("executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        assertThat(result.body().get("templates").toString()).contains("orders_v1", "orders_v2");
    }

    @Test
    void preservesOriginalDiscoveryRequestAndResponseContracts() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
            "templates", List.of(),
            "selectionProtocol", Map.of("allowedDecisions", List.of("accept", "refine", "reject"))));

        ApiServiceBridge.Result result = new ApiServiceBridge(discovery).query(Map.of(
            "query", "orders", "templateIds", List.of("orders_v1", "orders_v2"), "limit", 50));

        assertThat(result.body()).containsEntry("status", "NO_CANDIDATE").containsKey("selectionProtocol");
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(discovery).query(request.capture());
        assertThat(request.getValue()).containsEntry("templateIds", List.of("orders_v1", "orders_v2"))
            .containsEntry("limit", 50);
    }

    @Test
    void delegatesPublishedCustomQueryThroughItsOriginalAuthorizationContract() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        TemplateQueryMcpToolPublisher dynamic = mock(TemplateQueryMcpToolPublisher.class);
        when(dynamic.queryFromParent(org.mockito.ArgumentMatchers.eq("customer_template_query"),
            org.mockito.ArgumentMatchers.eq(ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME),
            org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "templates", List.of(Map.of("templateId", "authorized_customer_v1"))));
        ApiServiceBridge bridge = new ApiServiceBridge(discovery);
        bridge.configureDynamicTemplateQueries(dynamic);

        ApiServiceBridge.Result result = bridge.query(Map.of(
            TemplateQueryMcpToolPublisher.CHILD_TOOL_ARGUMENT, "customer_template_query",
            "query", "customer profile"));

        assertThat(result.body().get("templates").toString()).contains("authorized_customer_v1");
        verify(dynamic).queryFromParent(org.mockito.ArgumentMatchers.eq("customer_template_query"),
            org.mockito.ArgumentMatchers.eq(ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME),
            org.mockito.ArgumentMatchers.anyMap());
        verifyNoInteractions(discovery);
    }
}
