package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQuerySqlStep;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Called only after the existing child-template discovery scope has selected a query. */
@Service
@RequiredArgsConstructor
public class QueryMetadataContextService {
    private final SqlDatasourceConfigService datasources;
    private final DatasourceMetadataSearchService metadata;
    private final ObjectMapper json;
    private static final Pattern CYPHER_LABEL = Pattern.compile("[:|]\\s*(`(?:``|[^`])+`|[A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern CYPHER_PATTERN = Pattern.compile("([\\(\\[])([^\\)\\]{}]*)(?:\\{[^}]*})?[^\\)\\]]*[\\)\\]]");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+((?:[\\w]+|\"[^\"]+\"|`[^`]+`)(?:\\.(?:[\\w]+|\"[^\"]+\"|`[^`]+`)){0,2})");

    public Map<String, Object> context(DatabaseQueryConfig query) {
        if (query.getDatasourceId() == null || query.getDatasourceId().startsWith("http:"))
            return Map.of("reviewStatus", "UNMANAGED_DATASOURCE", "objects", List.of());
        var asset = datasources.getEnabled(query.getDatasourceId());
        List<DatabaseQuerySqlStep> steps;
        try {
            steps = query.getSqlStepsJson() == null || query.getSqlStepsJson().isBlank() ? List.of()
                : json.readValue(query.getSqlStepsJson(), new TypeReference<List<DatabaseQuerySqlStep>>() {});
        } catch (Exception ex) { return Map.of("reviewStatus", "INVALID_REFERENCE_CONFIG", "objects", List.of()); }
        if (steps.isEmpty()) {
            var step = new DatabaseQuerySqlStep(); step.setSqlContent(query.getSqlTemplate()); steps = List.of(step);
        }
        Set<String> refs = new LinkedHashSet<>(), namespaces = new LinkedHashSet<>();
        String type = asset.getDatabaseType();
        for (var step : steps) {
            if (!step.enabled()) continue;
            Map<String, Object> options = step.getQueryOptions();
            if ("opensearch".equals(type) || "elasticsearch".equals(type)) {
                if (options.get("index") != null) refs.add(String.valueOf(options.get("index")));
                continue;
            }
            String statement = step.getSqlContent() == null ? "" : step.getSqlContent();
            // Literal values and comments must not create additional metadata references.
            String code = statement.replaceAll("'(?:(?:'')|[^'])*'", " ").replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\r\\n]*", " ");
            if ("neo4j".equals(type)) {
                String database = String.valueOf(options.getOrDefault("database", "neo4j"));
                namespaces.add(database);
                var patterns = CYPHER_PATTERN.matcher(code);
                while (patterns.find()) {
                    var matcher = CYPHER_LABEL.matcher(patterns.group(2));
                    while (matcher.find()) {
                        String name = matcher.group(1).replace("``", "\u0000").replace("`", "").replace("\u0000", "`");
                        refs.add(database + "." + (patterns.group(1).equals("(") ? "NODE_LABEL" : "RELATIONSHIP_TYPE") + "." + name);
                    }
                }
            } else {
                String catalog = options.get("catalog") == null ? null : String.valueOf(options.get("catalog"));
                String schema = options.get("schema") == null ? null : String.valueOf(options.get("schema"));
                List<String> defaults = MetadataScopes.defaults(asset);
                if (catalog == null && !defaults.isEmpty()) {
                    String[] parts = defaults.get(0).split("\\.", 2); catalog = parts[0];
                    if (schema == null && parts.length > 1) schema = parts[1];
                }
                var matcher = SQL_TABLE.matcher(code);
                while (matcher.find()) {
                    String table = matcher.group(1).replace("`", "").replace("\"", "");
                    if ("trino".equals(type)) {
                        int parts = table.split("\\.").length;
                        if (parts == 1 && catalog != null && schema != null) refs.add(catalog + "." + schema + "." + table);
                        else if (parts == 2 && catalog != null) refs.add(catalog + "." + table);
                        else if (parts == 3) refs.add(table);
                    } else refs.add(table);
                }
            }
        }
        long reviewedAt = query.getUpdatedAt() == null ? 0 : query.getUpdatedAt().toEpochMilli();
        return metadata.context(asset, List.copyOf(refs), List.copyOf(namespaces), reviewedAt);
    }
}
