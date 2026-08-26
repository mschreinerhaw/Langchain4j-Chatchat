package com.chatchat.mcpserver.api;

import com.chatchat.common.bridge.RuntimeBridge;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.knowledge.SearchStatus;
import com.chatchat.common.knowledge.StandardSearchResult;
import com.chatchat.common.knowledge.template.TemplateServiceCall;
import com.chatchat.common.knowledge.template.TemplateServicePort;
import com.chatchat.common.knowledge.template.TemplateServiceResultStatus;
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
    void implementsVersionedRuntimeBridgeContract() {
        ApiServiceBridge bridge = new ApiServiceBridge(mock(ApiTemplateDiscoveryMcpToolPublisher.class));

        assertThat(RuntimeBridge.class).isAssignableFrom(ApiServiceBridge.class);
        assertThat(TemplateServicePort.class).isAssignableFrom(ApiServiceBridge.class);
        assertThat(bridge.bridgeContract().version()).isEqualTo("template_service_search.v1");
        assertThat(bridge.bridgeContract().protocol()).isEqualTo(KernelProtocolCatalog.TEMPLATE_SERVICE);
    }

    @Test
    void exposesTypedServiceToServiceCommunicationAlongsideLegacyMapProjection() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
            "templates", List.of(Map.of("templateId", "orders_v1"))));
        ApiServiceBridge bridge = new ApiServiceBridge(discovery);

        var response = bridge.invoke(TemplateServiceCall.search("orders", Map.of(), Map.of(), Map.of()),
            KernelDataScope.system("typed-request"));

        assertThat(response.successful()).isTrue();
        assertThat(response.data().requestId()).isEqualTo("typed-request");
        assertThat(response.data().status()).isEqualTo(TemplateServiceResultStatus.SUCCESS);
        assertThat(response.data().data()).containsKeys("searchResult", "templates");
    }

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
        assertThat(result.body()).containsKeys("searchResult", "searchSchemaVersion", "templateSchemaVersion", "events");
        assertThat(result.body().get("searchResult"))
            .isInstanceOfSatisfying(StandardSearchResult.class, search -> {
                assertThat(search.status()).isEqualTo(SearchStatus.FOUND);
                assertThat(search.hits()).hasSize(2);
            });
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
        assertThat(result.body().get("events").toString()).contains("TEMPLATE_NOT_FOUND", "SEARCH_TEMPLATE");
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

    @Test
    void surfacesMalformedCandidatesAsTemplateIdResolutionEvents() {
        ApiTemplateDiscoveryMcpToolPublisher discovery = mock(ApiTemplateDiscoveryMcpToolPublisher.class);
        when(discovery.query(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
            "templates", List.of(Map.of("title", "missing id"))));

        ApiServiceBridge.Result result = new ApiServiceBridge(discovery).query(Map.of("query", "orders"));

        assertThat(result.body()).containsEntry("status", "NO_CANDIDATE")
            .containsEntry("requiresModelReview", false);
        assertThat(result.body().get("events").toString()).contains("TEMPLATE_ID_MISSING", "SEARCH_TEMPLATE");
    }
}
