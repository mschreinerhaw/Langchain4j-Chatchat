package com.chatchat.mcpserver.search.admin;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataCatalog;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataSearchService;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.index.McpAssetLuceneIndexService;
import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.metadata.SqlMetadataSearchService;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSearchIndexAdminControllerTest {

    private final McpAssetLuceneIndexService assetIndexService = mock(McpAssetLuceneIndexService.class);
    private final McpTemplateLuceneIndexService templateIndexService = mock(McpTemplateLuceneIndexService.class);
    private final LuceneMcpSearchService luceneSearchService = mock(LuceneMcpSearchService.class);
    private final SqlMetadataSearchService sqlMetadataSearchService = mock(SqlMetadataSearchService.class);
    private final DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final DocumentSearchAdminClient documentSearchAdminClient = mock(DocumentSearchAdminClient.class);
    private final FinancialAssetCatalogService financialAssetCatalogService = mock(FinancialAssetCatalogService.class);
    private final EnterpriseMetadataCatalog enterpriseMetadataCatalog = mock(EnterpriseMetadataCatalog.class);
    private final EnterpriseMetadataSearchService enterpriseMetadataSearchService =
        mock(EnterpriseMetadataSearchService.class);
    private final McpSearchIndexAdminController controller = new McpSearchIndexAdminController(
        assetIndexService,
        templateIndexService,
        luceneSearchService,
        sqlMetadataSearchService,
        databaseQueryConfigService,
        datasourceConfigService,
        documentSearchAdminClient,
        financialAssetCatalogService,
        enterpriseMetadataCatalog,
        enterpriseMetadataSearchService,
        new ObjectMapper()
    );

    @Test
    void searchesEnterpriseMetadataTestIndex() {
        when(enterpriseMetadataCatalog.status()).thenReturn(Map.of(
            "indexName", "enterprise_metadata_catalog",
            "recordCount", 10068
        ));
        when(enterpriseMetadataSearchService.search(any())).thenReturn(Map.of(
            "schemaVersion", EnterpriseMetadataSearchService.RESULT_SCHEMA_VERSION,
            "backend", "opensearch",
            "count", 1,
            "results", List.of(Map.of(
                "id", "F001",
                "metadataType", "metadata_field",
                "name", "客户编码",
                "technicalName", "CUST_NUM",
                "dataType", "字符型",
                "status", "标准",
                "relevanceScore", 5.5D
            )),
            "evidenceObjects", List.of()
        ));

        ApiResponse<Map<String, Object>> response = controller.search(Map.of(
            "indexType", "enterprise_metadata",
            "query", "客户编码",
            "metadataType", "metadata_field",
            "status", "标准",
            "limit", 10
        ));

        assertThat(response.getData())
            .containsEntry("indexType", "enterprise_metadata")
            .containsEntry("physicalIndex", "enterprise_metadata_catalog")
            .containsEntry("catalogRecordCount", 10068)
            .containsEntry("count", 1);
        assertThat((List<?>) response.getData().get("results")).singleElement()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("technicalName", "CUST_NUM")
            .containsEntry("kind", "metadata_field")
            .containsEntry("assetType", "enterprise_metadata")
            .containsEntry("score", 5.5D);
    }

    @Test
    void searchesFinancialDataAssetIndex() {
        when(financialAssetCatalogService.search("行情", 10)).thenReturn(List.of(Map.of(
            "dataset_code", "market_quote_daily",
            "asset_name", "交易所每日行情",
            "business_description", "股票、基金、债券、回购、期权和指数行情",
            "database_name", "live_runtime_mcp",
            "table_name", "market_quote_daily"
        )));

        ApiResponse<Map<String, Object>> response = controller.search(Map.of(
            "indexType", "financial-data-asset",
            "query", "行情",
            "limit", 10
        ));

        verify(financialAssetCatalogService).search("行情", 10);
        assertThat(response.getData())
            .containsEntry("indexType", "financial_data_asset")
            .containsEntry("physicalIndex", "financial-data-asset")
            .containsEntry("count", 1);
        assertThat((List<?>) response.getData().get("results")).singleElement()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("name", "交易所每日行情")
            .containsEntry("assetType", "financial_data")
            .containsEntry("table", "market_quote_daily");
    }

    @ParameterizedTest
    @CsvSource({
        "ssh_host_assets,ssh_host,assets-ssh-host",
        "sql_datasource_assets,sql_datasource,assets-sql-datasource",
        "http_endpoint_http_assets,http_endpoint_http,assets-http-endpoint-http",
        "http_endpoint_microservice_assets,http_endpoint_microservice,assets-http-endpoint-microservice",
        "api_service_assets,api_service,assets-api-service"
    })
    void typedAssetIndexForcesDedicatedAssetType(String indexType, String assetType, String physicalIndex) {
        when(luceneSearchService.enabled()).thenReturn(true);
        when(luceneSearchService.assetIndexName(assetType)).thenReturn(physicalIndex);
        when(luceneSearchService.searchAssets(any()))
            .thenReturn(List.of(hit(assetType)));

        ApiResponse<Map<String, Object>> response = controller.search(Map.of(
            "indexType", indexType,
            "assetType", "sql_datasource",
            "query", "LiveData CDH",
            "env", "DEV",
            "limit", 10
        ));

        ArgumentCaptor<LuceneMcpSearchService.AssetSearchRequest> captor =
            ArgumentCaptor.forClass(LuceneMcpSearchService.AssetSearchRequest.class);
        verify(luceneSearchService).searchAssets(captor.capture());
        assertThat(captor.getValue().assetType()).isEqualTo(assetType);
        assertThat(captor.getValue().queryText()).isEqualTo("LiveData CDH");
        assertThat(captor.getValue().env()).isEqualTo("DEV");

        Map<String, Object> data = response.getData();
        assertThat(data).containsEntry("indexType", indexType);
        assertThat(data).containsEntry("assetType", assetType);
        assertThat(data).containsEntry("logicalIndex", "asset:" + assetType);
        assertThat(data).containsEntry("physicalIndex", physicalIndex);
        assertThat(data.get("request")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) data.get("request")).get("assetType")).isEqualTo(assetType);
    }

    @Test
    void httpEndpointAdminIndexSearchesBothBackendTechnicalPartitions() {
        when(luceneSearchService.enabled()).thenReturn(true);
        when(luceneSearchService.searchAssets(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> response = controller.search(Map.of(
            "indexType", "http_endpoint_assets", "query", "YARN", "limit", 10));

        ArgumentCaptor<LuceneMcpSearchService.AssetSearchRequest> captor =
            ArgumentCaptor.forClass(LuceneMcpSearchService.AssetSearchRequest.class);
        verify(luceneSearchService, org.mockito.Mockito.times(2)).searchAssets(captor.capture());
        assertThat(captor.getAllValues()).extracting(LuceneMcpSearchService.AssetSearchRequest::assetType)
            .containsExactlyInAnyOrder(
                McpAssetLuceneIndexService.HTTP_ASSET_INDEX_TYPE,
                McpAssetLuceneIndexService.MICROSERVICE_ASSET_INDEX_TYPE);
        assertThat(response.getData())
            .containsEntry("assetType", "http_endpoint")
            .containsEntry("physicalIndex", "assets-http-endpoint-*");
    }

    @Test
    void apiServicesIndexSearchesTemplatesInsteadOfDuplicatingApiServiceAssets() {
        when(luceneSearchService.enabled()).thenReturn(true);
        when(luceneSearchService.searchApiServiceTemplates(any()))
            .thenReturn(List.of(hit("api_service")));

        ApiResponse<Map<String, Object>> response = controller.search(Map.of(
            "indexType", "api_services",
            "query", "LiveData customer analysis",
            "limit", 10
        ));

        ArgumentCaptor<LuceneMcpSearchService.TemplateSearchRequest> captor =
            ArgumentCaptor.forClass(LuceneMcpSearchService.TemplateSearchRequest.class);
        verify(luceneSearchService).searchApiServiceTemplates(captor.capture());
        assertThat(captor.getValue().assetType()).isEqualTo("api_service");
        assertThat(captor.getValue().intentText()).isEqualTo("LiveData customer analysis");
        assertThat(response.getData())
            .containsEntry("indexType", "api_service")
            .containsEntry("count", 1);
    }

    @ParameterizedTest
    @CsvSource({
        "ssh-host,ssh_host",
        "sql_datasource_assets,sql_datasource",
        "http_endpoint,http_endpoint",
        "api_service_assets,api_service"
    })
    void typedAssetRebuildCallsDedicatedRefresh(String pathAssetType, String assetType) {
        when(assetIndexService.refresh(assetType)).thenReturn(Map.of(
            "enabled", true,
            "assetType", assetType,
            "indexed", 1
        ));

        ApiResponse<Map<String, Object>> response = controller.rebuildAssetIndex(pathAssetType);

        verify(assetIndexService).refresh(assetType);
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).containsEntry("assetType", assetType);
    }

    private LuceneMcpSearchService.SearchHit hit(String assetType) {
        return new LuceneMcpSearchService.SearchHit(
            assetType + "-1",
            "asset",
            3.5f,
            List.of("lucene_bm25:3.5"),
            assetType + "-1",
            "asset_registry",
            null,
            null,
            null,
            null,
            null,
            null,
            assetType,
            "LiveData CDH",
            "LiveData CDH asset",
            "system",
            null,
            "LOW"
        );
    }
}
