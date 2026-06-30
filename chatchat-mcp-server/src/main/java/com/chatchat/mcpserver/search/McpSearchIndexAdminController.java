package com.chatchat.mcpserver.search;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/assets/rebuild")
    public ApiResponse<Map<String, Object>> rebuildAssetIndex() {
        return ApiResponse.success(assetLuceneIndexService.refreshAll(), "MCP asset Lucene index rebuilt");
    }

    @PostMapping("/templates/rebuild")
    public ApiResponse<Map<String, Object>> rebuildTemplateIndex() {
        templateLuceneIndexService.refreshAll();
        return ApiResponse.success(Map.of("refreshed", true), "MCP template Lucene index rebuilt");
    }

    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> input = request == null ? Map.of() : new LinkedHashMap<>(request);
        String indexType = text(input.get("indexType"), "sql_metadata");
        int limit = boundedInt(input.get("limit"), 10, 1, 50);
        Map<String, Object> result;
        if ("templates".equalsIgnoreCase(indexType) || "template".equalsIgnoreCase(indexType)) {
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchTemplates(
                new LuceneMcpSearchService.TemplateSearchRequest(
                    text(input.get("assetType"), null),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    text(firstPresent(input, "query", "intentText", "q"), null),
                    limit
                )
            );
            result = searchResult("templates", input, hits);
        } else if ("assets".equalsIgnoreCase(indexType) || "asset".equalsIgnoreCase(indexType)) {
            List<LuceneMcpSearchService.SearchHit> hits = luceneSearchService.searchAssets(
                new LuceneMcpSearchService.AssetSearchRequest(
                    text(input.get("assetType"), null),
                    text(firstPresent(input, "query", "q"), null),
                    text(input.get("env"), null),
                    text(firstPresent(input, "dbType", "databaseType"), null),
                    stringList(input.get("labels")),
                    limit
                )
            );
            result = searchResult("assets", input, hits);
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
