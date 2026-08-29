package com.chatchat.mcpserver.api.publication;

import com.chatchat.mcpserver.api.category.ApiServiceCategoryService;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;

import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTemplateDiscoveryMcpToolPublisherTest {

    @Test
    void searchesEveryKeywordIndependentlyInsteadOfConcatenatingThem() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName("customer_analysis_api");
        config.setTitle("Customer analysis");
        config.setDescription("Trade history and asset snapshot");
        config.setEnabled(true);
        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of());

        Map<String, Object> result = publisher(configService, lucene).query(Map.of(
            "filters", Map.of("keywords", List.of("trade history", "asset snapshot"))
        ));

        ArgumentCaptor<LuceneMcpSearchService.TemplateSearchRequest> requests =
            ArgumentCaptor.forClass(LuceneMcpSearchService.TemplateSearchRequest.class);
        verify(lucene, times(2)).searchApiServiceTemplates(requests.capture());
        assertThat(requests.getAllValues()).extracting(LuceneMcpSearchService.TemplateSearchRequest::intentText)
            .containsExactly("trade history", "asset snapshot");
        assertThat(result.get("retrievalPlan").toString())
            .contains("independent_query_units", "search_each_unit_independently");
    }

    @Test
    void queryUsesIndependentIntentCandidatesAndConfiguredFieldMetadata() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-portfolio");
        config.setToolName("generic_query_api");
        config.setTitle("Generic data query");
        config.setDescription("Read configured data");
        config.setOutputSchemaJson("{\"type\":\"array\",\"properties\":{\"ZQSL\":{\"description\":\"证券持仓数量\"}}}");
        config.setCapabilitySpecJson("{\"description\":\"客户持仓分析\"}");
        config.setEnabled(true);
        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(false);

        Map<String, Object> result = publisher(configService, lucene).query(Map.of(
            "filters", Map.of("intentCandidates", List.of(Map.of(
                "intent", "customer 070200046604 transaction noise",
                "queries", List.of("客户持仓分析", "证券持仓数量")
            )))
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(result.toString()).contains("generic_query_api", "客户持仓分析", "retrievalVariants");
    }

    @Test
    void apiTemplateToolIsBusinessNamedReadOnlyDiscoveryTool() throws Exception {
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(
            mock(ApiServiceConfigService.class), mock(LuceneMcpSearchService.class));
        Method apiTemplateQueryTool = ApiTemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod("apiTemplateQueryTool");
        apiTemplateQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) apiTemplateQueryTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();

        assertThat(tool.name()).isEqualTo(ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME);
        assertThat(tool.description()).contains("API service templates");
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("targetKind")).isEqualTo("api_service");
        assertThat(meta.get("assetType")).isEqualTo("api_service");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(tool.inputSchema().toString()).contains("bilingualIntent", "intentZh", "intentEn");
    }

    @Test
    void queryReturnsApiTemplateMetadataWithoutRawExecutionSpec() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-1");
        config.setToolName("order_status_api");
        config.setTitle("订单状态查询");
        config.setDescription("Query order status by order id");
        config.setBusinessGroup("order_services");
        config.setBusinessGroupName("Order services");
        config.setBusinessGroupDescription("APIs for order status and fulfillment workflows");
        config.setMethod("GET");
        config.setUrlTemplate("https://internal.example/orders/{{orderId}}");
        config.setHeadersJson("{\"Authorization\":\"secret\"}");
        config.setBodyTemplate("{\"raw\":\"body\"}");
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
        config.setOutputSchemaJson("{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}");
        config.setCapabilitySpecJson("{\"capabilities\":[\"order_status\"]}");
        config.setDependencySpecJson("{\"dependsOn\":[]}");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("order_status_api", "template", 8.0f, List.of("lucene"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(configService, lucene);

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("businessGroup", "Order services")
        ));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("assetType")).isEqualTo("api_service");
        assertThat(result.toString()).contains("order_status_api", "订单状态查询", "parameterSchema");
        assertThat(result.toString()).doesNotContain("internal.example", "Authorization", "{\"raw\":\"body\"}");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("templates")).get(0);
        assertThat(first.get("businessGroup").toString()).contains("order_services", "Order services", "fulfillment");
        assertThat(first.get("requiredParameters")).isEqualTo(List.of("orderId"));
        assertThat(first.get("parameterContract").toString()).contains("api_template_execute.parameters", "orderId");
        assertThat(first.get("invocationExample").toString()).contains("api_template_execute", "order_status_api", "orderId");
        assertThat(first.get("outputSchema").toString()).contains("status");
        assertThat(first.get("capabilitySpec").toString()).contains("order_status");
        assertThat(first.get("dependencySpec").toString()).contains("dependsOn");
        assertThat(first.get("routing").toString()).contains("api_template_execute");
    }

    @Test
    void queryMatchesApiTemplateByBusinessGroupDescription() {
        ApiServiceConfig orderApi = new ApiServiceConfig();
        orderApi.setId("api-order");
        orderApi.setToolName("order_status_api");
        orderApi.setTitle("Order status API");
        orderApi.setDescription("Query order status by order id");
        orderApi.setBusinessGroup("order_services");
        orderApi.setBusinessGroupName("Order services");
        orderApi.setBusinessGroupDescription("fulfillment lifecycle APIs");
        orderApi.setMethod("GET");
        orderApi.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
        orderApi.setEnabled(true);
        ApiServiceConfig billingApi = new ApiServiceConfig();
        billingApi.setId("api-billing");
        billingApi.setToolName("invoice_status_api");
        billingApi.setTitle("Invoice status API");
        billingApi.setDescription("Query invoice status by invoice id");
        billingApi.setBusinessGroup("billing_services");
        billingApi.setBusinessGroupName("Billing services");
        billingApi.setBusinessGroupDescription("invoice settlement APIs");
        billingApi.setMethod("GET");
        billingApi.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(billingApi, orderApi));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("order_status_api", "template", 9.0f, List.of("lucene"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(configService, lucene);

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("groupDescription", "fulfillment lifecycle")
        ));

        assertThat(result.get("returnedCount")).isEqualTo(1);
        assertThat(result.toString())
            .contains("order_status_api", "order_services", "fulfillment lifecycle APIs")
            .doesNotContain("invoice_status_api");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("templates")).get(0);
        assertThat(first.get("templateId")).isEqualTo("order_status_api");
    }

    @Test
    void queryEnrichesRetrievalWithScopedApiServiceSignals() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-payment");
        config.setToolName("payment_status_api");
        config.setTitle("Payment status API");
        config.setDescription("Read payment callback and settlement status");
        config.setBusinessGroup("payment_services");
        config.setBusinessGroupName("Payment services");
        config.setBusinessGroupDescription("Payment callback and settlement APIs");
        config.setMethod("GET");
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"paymentId\":{\"type\":\"string\"}},\"required\":[\"paymentId\"]}");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("payment_status_api", "template", 8.0f, List.of("lucene"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(configService, lucene);

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("toolName", "payment_status_api", "intent", "status")
        ));

        assertThat(result.get("diagnostics").toString())
            .contains("retrievalSignals", "Payment status API", "settlement");
        verify(lucene, org.mockito.Mockito.atLeastOnce()).searchApiServiceTemplates(argThat(request -> request != null
            && request.intentText() != null && request.intentText().contains("payment status api")));
        verify(lucene, org.mockito.Mockito.atLeastOnce()).searchApiServiceTemplates(argThat(request -> request != null
            && request.intentText() != null && request.intentText().contains("settlement")));
    }

    @Test
    void queryRejectsRawApiExecutionFields() {
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(
            mock(ApiServiceConfigService.class), mock(LuceneMcpSearchService.class));

        assertThatThrownBy(() -> publisher.query(Map.of("filters", Map.of("urlTemplate", "https://example.com"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("api_template_query");
    }

    @Test
    void queryReturnsAuthorizedRegistryCandidatesWhenApiIndexHasNoHit() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName("order_status_api");
        config.setTitle("Order status API");
        config.setDescription("Query order status by order id");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of());
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(configService, lucene);

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of(
                "intentZh", "\u67e5\u8be2\u8ba2\u5355\u72b6\u6001",
                "intentEn", "query order status"
            )
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(result.get("templates").toString()).contains("order_status_api");
        assertThat(result.get("diagnostics").toString())
            .contains("hitCount=0", "candidateCount=1", "authorized_relevance_qualified_candidates");
        assertThat(result.get("templateSelectionPolicy").toString())
            .contains("runtimeSemanticReviewRequiredWhenMultiple=true", "mcpRelevanceIsAdmissionFilter=true");
        verify(lucene, org.mockito.Mockito.atLeastOnce()).searchApiServiceTemplates(argThat(request -> request != null
            && request.intentText() != null && request.intentText().contains("\u67e5\u8be2\u8ba2\u5355\u72b6\u6001")));
        verify(lucene, org.mockito.Mockito.atLeastOnce()).searchApiServiceTemplates(argThat(request -> request != null
            && request.intentText() != null && request.intentText().contains("query order status")));
    }

    @Test
    void queryDoesNotReturnUnrelatedRegistryCandidateWhenApiIndexHasNoHit() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setToolName("order_status_api");
        config.setTitle("Order status API");
        config.setDescription("Query order status by order id");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of());

        Map<String, Object> result = publisher(configService, lucene).query(Map.of(
            "filters", Map.of("intent", "rotate database encryption keys")
        ));

        assertThat(result).containsEntry("returnedCount", 0);
        assertThat((List<?>) result.get("templates")).isEmpty();
        assertThat(result.get("diagnostics").toString())
            .contains("retrievedCandidateCount=0", "qualifiedCandidateCount=0");
    }

    @Test
    void queryAcceptsStrongVectorEvidenceWithoutLexicalOverlap() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("api-capacity");
        config.setToolName("runtime_capacity_api");
        config.setTitle("Runtime capacity API");
        config.setDescription("Observe saturation pressure and allocation headroom");
        config.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(config));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit(
                "runtime_capacity_api", "template", 0.91F, List.of("opensearch_vector:0.91"))
        ));

        Map<String, Object> result = publisher(configService, lucene).query(Map.of(
            "filters", Map.of("intent", "运行资源耗尽预警")
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(result.get("templates").toString()).contains("runtime_capacity_api");
    }

    @Test
    void queryRestrictsResultsToTemplateIdsFromPriorAssetDiscovery() {
        ApiServiceConfig fundFlow = new ApiServiceConfig();
        fundFlow.setToolName("livedata_hisJyZjmxls");
        fundFlow.setTitle("资金流水");
        fundFlow.setDescription("查询客户资金流水");
        fundFlow.setEnabled(true);
        ApiServiceConfig tradeDetail = new ApiServiceConfig();
        tradeDetail.setToolName("livedata_cx_mncg_jgmxls");
        tradeDetail.setTitle("成交明细流水");
        tradeDetail.setDescription("查询客户成交明细");
        tradeDetail.setEnabled(true);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(tradeDetail, fundFlow));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("livedata_cx_mncg_jgmxls", "template", 20.0f, List.of("lucene")),
            new LuceneMcpSearchService.SearchHit("livedata_hisJyZjmxls", "template", 8.0f, List.of("lucene"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = publisher(configService, lucene);

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("intent", "查询客户资金流水"),
            "templateIds", List.of("livedata_hisJyZjmxls")
        ));

        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(result.get("requestedTemplateIds")).isEqualTo(List.of("livedata_hisJyZjmxls"));
        assertThat(result.get("templates").toString())
            .contains("livedata_hisJyZjmxls")
            .doesNotContain("livedata_cx_mncg_jgmxls");
    }

    @Test
    void queryReturnsCrossCategoryHitsAndUsesResolvedCategoryAsRankingSignal() {
        BusinessCategory orderCategory = category("category-order", "order_services", "订单服务");
        ApiServiceConfig orderApi = api("api-order", "order_status_api", orderCategory);
        BusinessCategory billingCategory = category("category-billing", "billing_services", "账单服务");
        ApiServiceConfig billingApi = api("api-billing", "invoice_status_api", billingCategory);

        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(orderApi, billingApi));
        ApiServiceCategoryService categoryService = mock(ApiServiceCategoryService.class);
        when(categoryService.resolve(any(), any())).thenReturn(
            new ApiServiceCategoryService.CategoryResolution(orderCategory, false,
                List.of(orderCategory, billingCategory)));
        when(categoryService.keywords(any())).thenReturn(List.of("订单", "order"));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("invoice_status_api", "template", 10.0f, List.of("lucene")),
            new LuceneMcpSearchService.SearchHit("order_status_api", "template", 8.0f, List.of("lucene"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class), configService, categoryService, lucene, new ObjectMapper(),
            mock(org.springframework.beans.factory.ObjectProvider.class));

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("category", "订单服务", "intent", "查询状态")));

        assertThat(result).containsEntry("categoryRequired", false);
        assertThat(result.get("selectedCategory").toString()).contains("order_services", "订单服务");
        assertThat(result.get("retrievalFlow").toString())
            .contains("business_category_resolution", "api_template_execution", "evidence_analysis",
                "crossCategoryResultsAllowed=true");
        assertThat(result.get("templates").toString())
            .contains("order_status_api", "invoice_status_api", "order_services", "billing_services");
    }

    @Test
    void queryKeepsSearchingWhenBusinessCategoryIsAmbiguous() {
        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(new ApiServiceConfig(), new ApiServiceConfig()));
        ApiServiceCategoryService categoryService = mock(ApiServiceCategoryService.class);
        when(categoryService.resolve(any(), any())).thenReturn(
            new ApiServiceCategoryService.CategoryResolution(null, true, List.of(
                category("category-order", "order_services", "订单服务"),
                category("category-billing", "billing_services", "账单服务"))));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class), configService, categoryService, lucene, new ObjectMapper(),
            mock(org.springframework.beans.factory.ObjectProvider.class));

        Map<String, Object> result = publisher.query(Map.of("filters", Map.of("intent", "查询状态")));

        assertThat(result).containsEntry("categoryRequired", false).containsEntry("returnedCount", 0);
        assertThat(result.get("categoryCandidates").toString()).contains("order_services", "billing_services");
        assertThat(result.get("diagnostics").toString())
            .contains("categoryAmbiguous=true", "categoryUsage=ranking_signal_and_model_selection_metadata");
        verify(lucene).searchApiServiceTemplates(any());
    }

    @Test
    void queryUsesDefaultCategoryAsSignalWithoutSuppressingCrossCategoryHits() {
        BusinessCategory fallback = category("category-default", "default", "默认分类");
        BusinessCategory orders = category("category-order", "order_services", "订单服务");
        ApiServiceConfig fallbackApi = api("api-default", "generic_lookup_api", fallback);
        ApiServiceConfig orderApi = api("api-order", "order_status_api", orders);
        ApiServiceConfigService configService = mock(ApiServiceConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of(fallbackApi, orderApi));
        ApiServiceCategoryService categoryService = mock(ApiServiceCategoryService.class);
        when(categoryService.resolve(any(), any())).thenReturn(
            new ApiServiceCategoryService.CategoryResolution(
                fallback, false, List.of(orders, fallback), true));
        when(categoryService.keywords(fallback)).thenReturn(List.of("默认", "未分类"));
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchApiServiceTemplates(any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("order_status_api", "template", 20.0f, List.of("opensearch_vector:20.0")),
            new LuceneMcpSearchService.SearchHit("generic_lookup_api", "template", 8.0f, List.of("default"))
        ));
        ApiTemplateDiscoveryMcpToolPublisher publisher = new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class), configService, categoryService, lucene, new ObjectMapper(),
            mock(org.springframework.beans.factory.ObjectProvider.class));

        Map<String, Object> result = publisher.query(Map.of(
            "filters", Map.of("category", "missing-category", "intent", "查询业务状态")));

        assertThat(result).containsEntry("categoryRequired", false).containsEntry("returnedCount", 1);
        assertThat(result.get("selectedCategory").toString()).contains("default");
        assertThat(result.get("diagnostics").toString())
            .contains("fallbackUsed=true", "fallbackCategory=default");
        assertThat(result.get("templates").toString())
            .contains("order_status_api", "order_services")
            .doesNotContain("generic_lookup_api");
    }

    private BusinessCategory category(String id, String code, String name) {
        BusinessCategory category = new BusinessCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setDescription(name + " API");
        category.setKeywordsJson("[]");
        category.setEnabled(true);
        return category;
    }

    private ApiServiceConfig api(String id, String toolName, BusinessCategory category) {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId(id);
        config.setToolName(toolName);
        config.setTitle(toolName);
        config.setDescription("Query status");
        config.setCategoryId(category.getId());
        config.setBusinessGroup(category.getCode());
        config.setBusinessGroupName(category.getName());
        config.setBusinessGroupDescription(category.getDescription());
        config.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        config.setEnabled(true);
        return config;
    }

    private ApiTemplateDiscoveryMcpToolPublisher publisher(ApiServiceConfigService configService,
                                                           LuceneMcpSearchService lucene) {
        ApiServiceCategoryService categoryService = mock(ApiServiceCategoryService.class);
        when(categoryService.resolve(any(), any())).thenReturn(
            new ApiServiceCategoryService.CategoryResolution(null, false, List.of()));
        when(categoryService.keywords(any())).thenReturn(List.of());
        return new ApiTemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class), configService, categoryService, lucene, new ObjectMapper(),
            mock(org.springframework.beans.factory.ObjectProvider.class));
    }
}
