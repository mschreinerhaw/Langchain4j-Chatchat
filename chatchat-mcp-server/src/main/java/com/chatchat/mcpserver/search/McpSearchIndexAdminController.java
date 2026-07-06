package com.chatchat.mcpserver.search;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mcp-search-index")
public class McpSearchIndexAdminController {

    private final McpAssetLuceneIndexService assetLuceneIndexService;
    private final McpTemplateLuceneIndexService templateLuceneIndexService;
    private final LuceneMcpSearchService luceneSearchService;
    private final SqlMetadataSearchService sqlMetadataSearchService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final DocumentSearchAdminClient documentSearchAdminClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/assets/rebuild")
    public ApiResponse<Map<String, Object>> rebuildAssetIndex() {
        return ApiResponse.success(assetLuceneIndexService.refreshAll(), "MCP asset Lucene index rebuilt");
    }

    @PostMapping("/assets/{assetType}/rebuild")
    public ApiResponse<Map<String, Object>> rebuildAssetIndex(@PathVariable("assetType") String assetType) {
        String normalizedAssetType = assetTypeForAssetIndex(assetType);
        if (normalizedAssetType == null) {
            return ApiResponse.badRequest("Unsupported MCP asset index type: " + assetType);
        }
        return ApiResponse.success(
            assetLuceneIndexService.refresh(normalizedAssetType),
            "MCP " + normalizedAssetType + " Lucene index rebuilt"
        );
    }

    @PostMapping("/templates/rebuild")
    public ApiResponse<Map<String, Object>> rebuildTemplateIndex() {
        templateLuceneIndexService.refreshAll();
        return ApiResponse.success(Map.of(
            "refreshed", true,
            "templateIndex", true,
            "databaseQueryIndex", true,
            "apiServiceIndex", true
        ), "MCP template Lucene indexes rebuilt");
    }

    @PostMapping("/database-queries/rebuild")
    public ApiResponse<Map<String, Object>> rebuildDatabaseQueryIndex() {
        templateLuceneIndexService.refreshDatabaseQueryTemplateIndex();
        return ApiResponse.success(Map.of("refreshed", true), "MCP database query Lucene index rebuilt");
    }

    @PostMapping("/api-services/rebuild")
    public ApiResponse<Map<String, Object>> rebuildApiServiceIndex() {
        templateLuceneIndexService.refreshApiServiceTemplateIndex();
        return ApiResponse.success(Map.of("refreshed", true), "MCP API service Lucene index rebuilt");
    }

    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> input = request == null ? Map.of() : new LinkedHashMap<>(request);
        String indexType = text(input.get("indexType"), "sql_metadata");
        int limit = boundedInt(input.get("limit"), 10, 1, 50);
        Map<String, Object> result;
        String assetIndexAssetType = assetTypeForAssetIndex(indexType);
        if (assetIndexAssetType != null) {
            Map<String, Object> effectiveInput = new LinkedHashMap<>(input);
            effectiveInput.put("assetType", assetIndexAssetType);
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
                new LuceneMcpSearchService.AssetSearchRequest(
                    assetIndexAssetType,
                    text(firstPresent(input, "query", "q"), null),
                    text(input.get("env"), null),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    stringList(input.get("labels")),
                    limit
                )
            );
            result = assetSearchResult(assetIndexName(assetIndexAssetType), effectiveInput, hits, assetIndexAssetType);
        } else if ("templates".equalsIgnoreCase(indexType) || "template".equalsIgnoreCase(indexType)) {
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchTemplates(
                new LuceneMcpSearchService.TemplateSearchRequest(
                    text(input.get("assetType"), null),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    text(firstPresent(input, "query", "intentText", "q"), null),
                    limit
                )
            );
            result = searchResult("templates", input, hits);
        } else if ("database_queries".equalsIgnoreCase(indexType)
            || "database_query".equalsIgnoreCase(indexType)
            || "database-query".equalsIgnoreCase(indexType)) {
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchDatabaseQueryTemplates(
                new LuceneMcpSearchService.TemplateSearchRequest(
                    text(input.get("assetType"), "database_query"),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    text(firstPresent(input, "query", "intentText", "q"), null),
                    limit
                )
            );
            result = databaseQuerySearchResult("database_query", input, hits);
        } else if ("api_services".equalsIgnoreCase(indexType)
            || "api_service".equalsIgnoreCase(indexType)
            || "api-service".equalsIgnoreCase(indexType)) {
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchApiServiceTemplates(
                new LuceneMcpSearchService.TemplateSearchRequest(
                    text(input.get("assetType"), "api_service"),
                    null,
                    text(firstPresent(input, "query", "intentText", "q"), null),
                    limit
                )
            );
            result = searchResult("api_service", input, hits);
        } else if ("assets".equalsIgnoreCase(indexType) || "asset".equalsIgnoreCase(indexType)) {
            String requestedAssetType = text(input.get("assetType"), null);
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
                new LuceneMcpSearchService.AssetSearchRequest(
                    requestedAssetType,
                    text(firstPresent(input, "query", "q"), null),
                    text(input.get("env"), null),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    stringList(input.get("labels")),
                    limit
                )
            );
            result = assetSearchResult("assets", input, hits, requestedAssetType);
        } else if ("document_search".equalsIgnoreCase(indexType)
            || "document-search".equalsIgnoreCase(indexType)
            || "documents".equalsIgnoreCase(indexType)) {
            result = documentSearchAdminClient.search(input, limit);
        } else {
            Map<String, Object> arguments = new LinkedHashMap<>();
            copy(input, arguments, "query", "q", "tableName", "table_name", "database", "schema", "assetName", "limit", "includeColumns");
            Map<String, Object> executionContext = new LinkedHashMap<>();
            copy(input, executionContext, "assetName", "env", "databaseType", "dbType", "database", "schema", "tableName");
            if (!executionContext.isEmpty()) {
                arguments.put("executionContext", executionContext);
            }
            result = new LinkedHashMap<>(sqlMetadataSearchService.search(arguments));
            result.put("indexType", "sql_metadata");
            result.put("luceneEnabled", luceneSearchService.enabled());
            result.put("request", arguments);
        }
        return ApiResponse.success(result, "MCP search index query completed");
    }

    private String assetTypeForAssetIndex(String indexType) {
        if (indexType == null || indexType.isBlank()) {
            return null;
        }
        String normalized = indexType.trim().toLowerCase(java.util.Locale.ROOT)
            .replace('-', '_')
            .replace(':', '_');
        return switch (normalized) {
            case "ssh_host", "ssh_host_assets", "ssh_assets", "server_assets", "service_assets", "asset_ssh_host", "assets_ssh_host" -> "ssh_host";
            case "sql_datasource", "sql_datasource_assets", "database_assets", "db_assets", "asset_sql_datasource", "assets_sql_datasource" -> "sql_datasource";
            case "http_endpoint", "http_endpoint_assets", "http_assets", "asset_http_endpoint", "assets_http_endpoint" -> "http_endpoint";
            case "api_service", "api_service_assets", "api_assets", "asset_api_service", "assets_api_service" -> "api_service";
            default -> null;
        };
    }

    private String assetIndexName(String assetType) {
        return switch (assetType) {
            case "ssh_host" -> "ssh_host_assets";
            case "sql_datasource" -> "sql_datasource_assets";
            case "http_endpoint" -> "http_endpoint_assets";
            case "api_service" -> "api_service_assets";
            default -> "assets";
        };
    }

    private Map<String, Object> assetSearchResult(String indexType,
                                                  Map<String, Object> request,
                                                  List<LuceneMcpSearchService.SearchHit> hits,
                                                  String assetType) {
        Map<String, Object> value = searchResult(indexType, request, hits);
        value.put("assetType", assetType == null ? "all" : assetType);
        value.put("logicalIndex", assetType == null ? "asset:*" : "asset:" + assetType);
        value.put("physicalIndex", assetType == null ? "assets-*" : luceneSearchService.assetIndexName(assetType));
        return value;
    }

    private Map<String, Object> searchResult(String indexType,
                                             Map<String, Object> request,
                                             List<LuceneMcpSearchService.SearchHit> hits) {
        List<Map<String, Object>> results = hits == null ? List.of() : hits.stream()
            .map(hit -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", hit.id());
                value.put("kind", hit.kind());
                value.put("score", hit.score());
                value.put("reasons", hit.reasons());
                value.put("documentId", hit.documentId());
                value.put("source", hit.source());
                value.put("assetType", hit.assetType());
                value.put("name", hit.name());
                value.put("description", hit.description());
                value.put("category", hit.category());
                value.put("dbType", hit.dbType());
                value.put("riskLevel", hit.riskLevel());
                value.put("resultId", hit.resultId());
                value.put("database", hit.database());
                value.put("table", hit.table());
                value.put("fullPath", hit.fullPath());
                value.put("tableComment", hit.tableComment());
                value.put("databaseComment", hit.databaseComment());
                return value;
            })
            .toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("indexType", indexType);
        value.put("luceneEnabled", luceneSearchService.enabled());
        value.put("request", request);
        value.put("count", results.size());
        value.put("results", results);
        return value;
    }

    private Map<String, Object> databaseQuerySearchResult(String indexType,
                                                          Map<String, Object> request,
                                                          List<LuceneMcpSearchService.SearchHit> hits) {
        List<Map<String, Object>> templates = new java.util.ArrayList<>();
        List<Map<String, Object>> results = hits == null ? List.of() : hits.stream()
            .map(hit -> {
                Map<String, Object> value = hitResult(hit);
                datasourceById(hit.id()).ifPresent(datasource -> {
                    Map<String, Object> executionContext = sqlExecutionContext(datasource);
                    List<Map<String, Object>> associated = associatedTemplates(datasource, executionContext);
                    value.put("datasourceAsset", datasourceAsset(datasource));
                    value.put("sqlExecutionContext", executionContext);
                    value.put("associatedTemplates", associated);
                    templates.addAll(associated);
                });
                return value;
            })
            .toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("indexType", indexType);
        value.put("luceneEnabled", luceneSearchService.enabled());
        value.put("request", request);
        value.put("count", results.size());
        value.put("results", results);
        value.put("templates", templates);
        return value;
    }

    private Map<String, Object> hitResult(LuceneMcpSearchService.SearchHit hit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", hit.id());
        value.put("kind", hit.kind());
        value.put("score", hit.score());
        value.put("reasons", hit.reasons());
        value.put("documentId", hit.documentId());
        value.put("source", hit.source());
        value.put("assetType", hit.assetType());
        value.put("name", hit.name());
        value.put("description", hit.description());
        value.put("category", hit.category());
        value.put("dbType", hit.dbType());
        value.put("riskLevel", hit.riskLevel());
        value.put("resultId", hit.resultId());
        value.put("database", hit.database());
        value.put("table", hit.table());
        value.put("fullPath", hit.fullPath());
        value.put("tableComment", hit.tableComment());
        value.put("databaseComment", hit.databaseComment());
        return value;
    }

    private java.util.Optional<SqlDatasourceConfig> datasourceById(String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(datasourceConfigService.getEnabled(datasourceId));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private Map<String, Object> datasourceAsset(SqlDatasourceConfig datasource) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "sql_datasource");
        value.put("id", datasource.getId());
        value.put("name", datasource.getName());
        value.put("title", text(datasource.getTitle(), datasource.getName()));
        value.put("toolName", datasource.getToolName());
        value.put("environment", datasource.getEnvironment());
        value.put("databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()));
        return value;
    }

    private Map<String, Object> sqlExecutionContext(SqlDatasourceConfig datasource) {
        String dbType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assetName", text(datasource.getName(), text(datasource.getTitle(), datasource.getToolName())));
        value.put("env", text(datasource.getEnvironment(), ""));
        value.put("environment", text(datasource.getEnvironment(), ""));
        value.put("databaseType", dbType);
        value.put("dbType", dbType);
        return value;
    }

    private List<Map<String, Object>> associatedTemplates(SqlDatasourceConfig datasource,
                                                          Map<String, Object> executionContext) {
        return databaseQueryConfigService.listEnabled().stream()
            .filter(config -> datasource.getId() != null && datasource.getId().equals(config.getDatasourceId()))
            .map(config -> databaseQueryTemplateSummary(config, executionContext))
            .toList();
    }

    private Map<String, Object> databaseQueryTemplateSummary(DatabaseQueryConfig config,
                                                             Map<String, Object> executionContext) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", config.getId());
        value.put("templateId", config.getToolName());
        value.put("mcpToolName", config.getToolName());
        value.put("name", text(config.getTitle(), config.getToolName()));
        value.put("description", text(config.getDescription(), ""));
        Map<String, Object> businessGroup = new LinkedHashMap<>();
        businessGroup.put("code", text(config.getBusinessGroup(), "default"));
        businessGroup.put("name", text(config.getBusinessGroupName(), text(config.getBusinessGroup(), "default")));
        businessGroup.put("description", text(config.getBusinessGroupDescription(), ""));
        value.put("businessGroup", businessGroup);
        value.put("intent", text(config.getTemplateIntent(), "general_query"));
        value.put("databaseType", SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()));
        value.put("riskLevel", text(config.getRiskLevel(), "read_only"));
        value.put("owner", text(config.getOwner(), "admin"));
        Map<String, Object> parameterSchema = parameterSchema(config.getInputSchemaJson());
        value.put("parameterSchema", parameterSchema);
        value.put("requiredParameters", requiredParameters(parameterSchema));
        Map<String, Object> sqlExecutionBinding = new LinkedHashMap<>();
        sqlExecutionBinding.put("toolName", "sql_query_execute");
        sqlExecutionBinding.put("templateId", config.getToolName());
        sqlExecutionBinding.put("executionContext", executionContext);
        sqlExecutionBinding.put("parametersPath", "parameters");
        value.put("sqlExecutionBinding", sqlExecutionBinding);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("mode", "direct_mcp_tool");
        execution.put("callTool", config.getToolName());
        execution.put("executionContext", executionContext);
        value.put("execution", execution);
        return value;
    }

    private Map<String, Object> parameterSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<>() {});
            return schema == null || schema.isEmpty()
                ? Map.of("type", "object", "properties", Map.of(), "required", List.of())
                : schema;
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
    }

    private List<String> requiredParameters(Map<String, Object> parameterSchema) {
        Object required = parameterSchema == null ? null : parameterSchema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                target.put(key, value);
            }
        }
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).split("[,，\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }
}
