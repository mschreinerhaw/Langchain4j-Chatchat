package com.chatchat.mcpserver.sql;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.search.McpAssetLuceneIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/sql")
public class SqlAdminController {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlTemplateService templateService;
    private final SqlMcpToolPublisher publisher;
    private final SqlQueryExecuteService queryExecuteService;
    private final McpAssetLuceneIndexService assetLuceneIndexService;
    private final MetadataIndexService metadataIndexService;
    private final SqlMetadataAssetRegistryService metadataAssetRegistryService;

    @GetMapping("/datasources")
    public ApiResponse<List<SqlDatasourceConfig>> listDatasources() {
        return ApiResponse.success(datasourceConfigService.listAll());
    }

    @PostMapping("/datasources")
    public ApiResponse<SqlDatasourceConfig> createDatasource(@RequestBody SqlDatasourceConfig request) {
        SqlDatasourceConfig saved = datasourceConfigService.create(request);
        refreshMetadataWhenRequested(saved);
        publisher.refresh();
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(saved, "SQL datasource created");
    }

    @PutMapping("/datasources/{id}")
    public ApiResponse<SqlDatasourceConfig> updateDatasource(@PathVariable("id") String id,
                                                             @RequestBody SqlDatasourceConfig request) {
        SqlDatasourceConfig saved = datasourceConfigService.update(id, request);
        refreshMetadataWhenRequested(saved);
        publisher.refresh();
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(saved, "SQL datasource updated");
    }

    @DeleteMapping("/datasources/{id}")
    public ApiResponse<Void> deleteDatasource(@PathVariable("id") String id) {
        datasourceConfigService.delete(id);
        publisher.refresh();
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(null, "SQL datasource deleted");
    }

    @PostMapping("/datasources/test")
    public ApiResponse<SqlQueryResult> testDatasource(@RequestBody SqlDatasourceConfig request) {
        return ApiResponse.success(queryExecuteService.testConnection(request), "SQL datasource tested");
    }

    @PostMapping("/datasources/{id}/metadata/refresh")
    public ApiResponse<MetadataIndexService.MetadataRefreshResult> refreshDatasourceMetadata(@PathVariable("id") String id) {
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(id);
        MetadataIndexService.MetadataRefreshResult result = metadataIndexService.refreshDatasource(datasource);
        assetLuceneIndexService.refreshAll();
        String message = result.error() == null || result.error().isBlank()
            ? "SQL datasource metadata refreshed"
            : "SQL datasource metadata refresh completed with errors";
        return ApiResponse.success(result, message);
    }

    @GetMapping("/datasources/{id}/metadata/assets")
    public ApiResponse<List<SqlMetadataAssetRegistry>> listDatasourceMetadataAssets(@PathVariable("id") String id) {
        datasourceConfigService.getById(id);
        return ApiResponse.success(metadataAssetRegistryService.listByDatasource(id));
    }

    @PostMapping("/datasources/{id}/metadata/assets")
    public ApiResponse<SqlMetadataAssetRegistry> createDatasourceMetadataAsset(@PathVariable("id") String id,
                                                                               @RequestBody SqlMetadataAssetRegistry request) {
        datasourceConfigService.getById(id);
        SqlMetadataAssetRegistry saved = metadataAssetRegistryService.save(id, request);
        metadataIndexService.invalidate(id);
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(saved, "SQL metadata asset registered");
    }

    @PutMapping("/datasources/{id}/metadata/assets/{assetId}")
    public ApiResponse<SqlMetadataAssetRegistry> updateDatasourceMetadataAsset(@PathVariable("id") String id,
                                                                               @PathVariable("assetId") String assetId,
                                                                               @RequestBody SqlMetadataAssetRegistry request) {
        datasourceConfigService.getById(id);
        request.setId(assetId);
        SqlMetadataAssetRegistry saved = metadataAssetRegistryService.save(id, request);
        metadataIndexService.invalidate(id);
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(saved, "SQL metadata asset updated");
    }

    @DeleteMapping("/datasources/{id}/metadata/assets/{assetId}")
    public ApiResponse<Void> deleteDatasourceMetadataAsset(@PathVariable("id") String id,
                                                           @PathVariable("assetId") String assetId) {
        datasourceConfigService.getById(id);
        metadataAssetRegistryService.delete(id, assetId);
        metadataIndexService.invalidate(id);
        assetLuceneIndexService.refreshAll();
        return ApiResponse.success(null, "SQL metadata asset deleted");
    }

    @GetMapping("/templates")
    public ApiResponse<List<SqlTemplateConfig>> listTemplates() {
        return ApiResponse.success(templateService.listAll());
    }

    @PostMapping("/templates")
    public ApiResponse<SqlTemplateConfig> createTemplate(@RequestBody SqlTemplateConfig request) {
        return ApiResponse.success(templateService.save(request), "SQL template created");
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<SqlTemplateConfig> updateTemplate(@PathVariable("id") String id,
                                                         @RequestBody SqlTemplateConfig request) {
        return ApiResponse.success(templateService.update(id, request), "SQL template updated");
    }

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable("id") String id) {
        templateService.delete(id);
        return ApiResponse.success(null, "SQL template deleted");
    }

    @PostMapping("/refresh-tools")
    public ApiResponse<Map<String, Object>> refreshTools() {
        publisher.refresh();
        Map<String, Object> indexSummary = assetLuceneIndexService.refreshAll();
        return ApiResponse.success(Map.of("refreshed", true, "assetIndex", indexSummary), "SQL MCP tools refreshed");
    }

    private void refreshMetadataWhenRequested(SqlDatasourceConfig datasource) {
        if (datasource == null || !datasource.isEnabled() || !datasource.isMetadataAutoRefreshEnabled()) {
            return;
        }
        try {
            metadataIndexService.refreshDatasource(datasource);
        } catch (Exception ex) {
            log.warn("SQL datasource metadata auto refresh after save failed: datasourceId={}, error={}",
                datasource.getId(), ex.getMessage());
        }
    }
}
