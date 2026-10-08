package com.chatchat.mcpserver.database.execution;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.definition.DatabaseQuerySqlStep;
import com.chatchat.mcpserver.datacapability.connection.QueryConnectionService;
import com.chatchat.mcpserver.datacapability.definition.CapabilityDefinition;
import com.chatchat.mcpserver.datacapability.definition.CapabilityType;
import com.chatchat.mcpserver.datacapability.execution.CapabilityAdapter;
import com.chatchat.mcpserver.datacapability.graph.GraphQueryAdapter;
import com.chatchat.mcpserver.datacapability.unstructured.OpenSearchQueryAdapter;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts existing query workflows to central assets without a second query registry. */
@Service
@RequiredArgsConstructor
public class DatabaseQuerySourceAdapterService {
    public static final String HTTP_PREFIX = "http:";
    private final SqlDatasourceConfigService sqlAssets;
    private final QueryConnectionService httpAssets;
    private final GraphQueryAdapter graph;
    private final OpenSearchQueryAdapter search;

    public static boolean isHttp(String reference) {
        return reference != null && reference.startsWith(HTTP_PREFIX);
    }

    public List<SourceView> list() {
        List<SourceView> sources = new ArrayList<>();
        sqlAssets.listAll().forEach(asset -> sources.add(new SourceView(asset.getId(), asset.getName(),
            asset.getDatabaseType(), sqlType(asset.getDatabaseType(), asset.getJdbcUrl()).name(), "SQL", asset.isEnabled())));
        httpAssets.list(null).forEach(asset -> sources.add(new SourceView(HTTP_PREFIX + asset.id(), asset.name(),
            asset.type() == CapabilityType.GRAPH ? "neo4j" : "opensearch", asset.type().name(),
            asset.type() == CapabilityType.GRAPH ? "Cypher" : "JSON DSL", asset.enabled())));
        return sources;
    }

    public SourceView getEnabled(String reference) {
        if (!isHttp(reference)) {
            var asset = sqlAssets.getEnabled(reference);
            return new SourceView(reference, asset.getName(), asset.getDatabaseType(),
                sqlType(asset.getDatabaseType(), asset.getJdbcUrl()).name(), "SQL", true);
        }
        String id = reference.substring(HTTP_PREFIX.length());
        var asset = httpAssets.list(null).stream().filter(item -> item.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("查询数据源资产不存在: " + reference));
        httpAssets.get(id, asset.type(), true);
        return new SourceView(reference, asset.name(), asset.type() == CapabilityType.GRAPH ? "neo4j" : "opensearch",
            asset.type().name(), asset.type() == CapabilityType.GRAPH ? "Cypher" : "JSON DSL", true);
    }

    private CapabilityType sqlType(String databaseType, String url) {
        return "trino".equalsIgnoreCase(databaseType) || (url != null && url.startsWith("jdbc:trino:"))
            ? CapabilityType.TRINO : CapabilityType.RELATIONAL;
    }

    public void validate(String reference, List<DatabaseQuerySqlStep> steps, String legacyQuery) {
        SourceView source = getEnabled(reference);
        if (isHttp(reference)) {
            if (steps.isEmpty()) throw new IllegalArgumentException("图库和检索查询请配置查询步骤及查询选项");
            for (var step : steps) {
                if (step.enabled()) adapter(source).validate(definition(source, step.getSqlContent(), step.getQueryOptions(), 30, 50));
            }
        } else {
            for (var step : steps) validateSqlOptions(step.getQueryOptions());
        }
    }

    public void validateSqlOptions(Map<String, Object> options) {
        for (String key : List.of("catalog", "schema")) {
            Object value = options.get(key);
            if (value != null && !value.toString().isBlank() && !value.toString().matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid query " + key);
        }
    }

    public ToolOutput execute(Map<String, Object> request) {
        long started = System.currentTimeMillis();
        try {
            SourceView source = getEnabled(String.valueOf(request.get("datasource_id")));
            @SuppressWarnings("unchecked")
            Map<String, Object> options = request.get("query_options") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = request.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
            CapabilityDefinition definition = definition(source, String.valueOf(request.get("sql")), options,
                number(request.get("timeoutSeconds"), 30), number(request.get("max_rows"), 50));
            CapabilityAdapter adapter = adapter(source);
            adapter.validate(definition);
            var result = adapter.execute(definition, parameters);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sql", definition.query());
            data.put("queryLanguage", source.queryLanguage());
            data.put("rows", result.rows());
            data.put("columns", result.rows().stream().flatMap(row -> row.keySet().stream()).distinct().toList());
            data.put("rowCount", result.rows().size());
            data.put("maxRows", definition.maxRows());
            data.put("possiblyTruncated", result.truncated());
            data.put("readOnly", true);
            data.put("diagnostics", result.metadata());
            ToolOutput output = ToolOutput.success(data, "查询完成");
            output.setExecutionTimeMs(System.currentTimeMillis() - started);
            return output;
        } catch (Exception ex) {
            ToolOutput output = ToolOutput.failure(ex.getMessage());
            output.setExecutionTimeMs(System.currentTimeMillis() - started);
            return output;
        }
    }

    private CapabilityDefinition definition(SourceView source, String query, Map<String, Object> options, int timeout, int maxRows) {
        return new CapabilityDefinition("workflow_step", "查询步骤", "统一查询工作台", CapabilityType.valueOf(source.type()),
            null, source.id().substring(HTTP_PREFIX.length()), query, null, null, options,
            Math.max(1, Math.min(300, timeout)), Math.max(1, Math.min(1000, maxRows)), true, false, false);
    }

    private CapabilityAdapter adapter(SourceView source) {
        return switch (CapabilityType.valueOf(source.type())) {
            case GRAPH -> graph;
            case UNSTRUCTURED -> search;
            default -> throw new IllegalArgumentException("数据源未配置 HTTP 查询适配器");
        };
    }

    private int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    public record SourceView(String id, String name, String databaseType, String type, String queryLanguage, boolean enabled) {}
}
