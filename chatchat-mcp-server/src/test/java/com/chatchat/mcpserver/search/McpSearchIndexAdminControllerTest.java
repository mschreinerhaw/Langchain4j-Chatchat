package com.chatchat.mcpserver.search;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final McpSearchIndexAdminController controller = new McpSearchIndexAdminController(
        assetIndexService,
        templateIndexService,
        luceneSearchService,
        sqlMetadataSearchService,
        databaseQueryConfigService,
        datasourceConfigService,
        documentSearchAdminClient,
        new ObjectMapper()
    );

    @ParameterizedTest
    @CsvSource({
        "ssh_host_assets,ssh_host,assets-ssh-host",
        "sql_datasource_assets,sql_datasource,assets-sql-datasource",
        "http_endpoint_assets,http_endpoint,assets-http-endpoint",
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
