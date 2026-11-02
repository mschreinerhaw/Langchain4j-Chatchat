package com.chatchat.mcpserver.sql.datasource;

import com.chatchat.mcpserver.datacapability.connection.NativeDatabaseClient;
import com.chatchat.mcpserver.sql.execution.SqlQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class NativeQueryDatasourceProbe {
    private final NativeDatabaseClient client;

    public SqlQueryResult test(SqlDatasourceConfig asset) {
        long started = System.currentTimeMillis();
        int timeout = Math.max(1, Math.min(60, asset.getDefaultTimeoutSeconds()));
        String query = "neo4j".equals(NativeQueryDatasource.type(asset)) ? "RETURN 1 AS probe" : "GET /";
        try {
            var connection = NativeQueryDatasource.connection(asset);
            var response = "neo4j".equals(NativeQueryDatasource.type(asset))
                ? client.cypher(connection, probeDatabase(asset), List.of(Map.of("statement", query)), timeout)
                : client.get(connection, "/", timeout);
            if (response == null || !response.isObject() || response.has("error") || response.path("errors").size() > 0)
                throw new IllegalArgumentException("原生查询驱动连接测试失败");
            if ("neo4j".equals(NativeQueryDatasource.type(asset)) && response.path("results").isEmpty())
                throw new IllegalArgumentException("Neo4j 查询探测未返回结果");
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            if (!"neo4j".equals(NativeQueryDatasource.type(asset))) {
                try {
                    var resolved = client.get(connection, "/_resolve/index/*?expand_wildcards=open", timeout);
                    if (resolved == null || !resolved.path("indices").isArray() || !resolved.path("aliases").isArray())
                        throw new IllegalStateException("索引发现接口返回不完整");
                    var options = new TreeSet<String>();
                    for (String group : List.of("indices", "aliases", "data_streams")) {
                        for (var entry : resolved.path(group)) {
                            String name = entry.path("name").asText("");
                            boolean hidden = false;
                            for (var attribute : entry.path("attributes"))
                                if ("hidden".equals(attribute.asText()) || "system".equals(attribute.asText())) hidden = true;
                            if (!name.isBlank() && !name.startsWith(".") && !hidden) options.add(name);
                            if (options.size() > 20000) throw new IllegalStateException("索引数量超过 20000，请手工指定范围");
                        }
                    }
                    diagnostics.put("metadataScopeOptions", List.copyOf(options));
                } catch (Exception ex) {
                    // A read-only search account may lack view_index_metadata permission.
                    diagnostics.put("metadataDiscoveryWarning", "连接成功，索引发现失败；可手工填写索引范围：" + ex.getMessage());
                }
            }
            return result(asset, timeout, query, started, null, diagnostics);
        } catch (Exception ex) { return result(asset, timeout, query, started,
            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), Map.of()); }
    }

    private String probeDatabase(SqlDatasourceConfig asset) {
        String scope = asset.getMetadataScopeValue();
        return scope == null || scope.isBlank() ? "neo4j" : scope.split(",")[0].trim();
    }

    private SqlQueryResult result(SqlDatasourceConfig asset, int timeout, String query, long started, String error,
                                  Map<String, Object> discovery) {
        boolean success = error == null;
        Map<String, Object> diagnostics = new LinkedHashMap<>(discovery);
        diagnostics.put("driver", String.valueOf(asset.getDriverClass()));
        diagnostics.put("queryLanguage", "neo4j".equals(NativeQueryDatasource.type(asset)) ? "Cypher" : "JSON DSL");
        return new SqlQueryResult(success, asset.getId(), asset.getName(), asset.getToolName(), asset.getEnvironment(), query, query,
            timeout, 1, List.of("probe"), success ? List.of(Map.of("probe", "connected")) : List.of(), success ? 1 : 0,
            false, System.currentTimeMillis() - started, null, null, error,
            diagnostics);
    }
}
