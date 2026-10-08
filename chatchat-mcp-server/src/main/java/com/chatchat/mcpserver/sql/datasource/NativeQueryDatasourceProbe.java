package com.chatchat.mcpserver.sql.datasource;

import com.chatchat.mcpserver.datacapability.connection.QueryHttpClient;
import com.chatchat.mcpserver.sql.execution.SqlQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NativeQueryDatasourceProbe {
    private final QueryHttpClient http;

    public SqlQueryResult test(SqlDatasourceConfig asset) {
        long started = System.currentTimeMillis();
        int timeout = Math.max(1, Math.min(60, asset.getDefaultTimeoutSeconds()));
        String query = "neo4j".equals(NativeQueryDatasource.type(asset)) ? "RETURN 1 AS probe" : "GET /";
        try {
            var connection = NativeQueryDatasource.connection(asset);
            var response = "neo4j".equals(NativeQueryDatasource.type(asset))
                ? http.post(connection, "/db/neo4j/tx/commit", Map.of("statements", List.of(Map.of("statement", query))), timeout, Map.of())
                : http.get(connection, "/", timeout);
            if (response == null || !response.isObject() || response.has("error") || response.path("errors").size() > 0)
                throw new IllegalArgumentException("原生查询驱动连接测试失败");
            if ("neo4j".equals(NativeQueryDatasource.type(asset)) && response.path("results").isEmpty())
                throw new IllegalArgumentException("Neo4j 查询探测未返回结果");
            return result(asset, timeout, query, started, null);
        } catch (Exception ex) { return result(asset, timeout, query, started,
            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
    }

    private SqlQueryResult result(SqlDatasourceConfig asset, int timeout, String query, long started, String error) {
        boolean success = error == null;
        return new SqlQueryResult(success, asset.getId(), asset.getName(), asset.getToolName(), asset.getEnvironment(), query, query,
            timeout, 1, List.of("probe"), success ? List.of(Map.of("probe", "connected")) : List.of(), success ? 1 : 0,
            false, System.currentTimeMillis() - started, null, null, error,
            Map.of("driver", String.valueOf(asset.getDriverClass()), "queryLanguage", "neo4j".equals(NativeQueryDatasource.type(asset)) ? "Cypher" : "JSON DSL"));
    }
}
