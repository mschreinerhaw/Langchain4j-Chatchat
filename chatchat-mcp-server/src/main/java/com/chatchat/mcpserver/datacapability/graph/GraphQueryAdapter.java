package com.chatchat.mcpserver.datacapability.graph;

import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.CapabilityAdapter;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import java.util.*;

@Component @RequiredArgsConstructor
public class GraphQueryAdapter implements CapabilityAdapter {
    private final QueryConnectionService connections;
    private final QueryHttpClient http;
    private final ObjectMapper json;
    @Override public CapabilityType type() { return CapabilityType.GRAPH; }
    @Override public void validate(CapabilityDefinition d) {
        connections.get(d.connectionId(), type(), false);
        String query = d.query();
        if (query == null || !query.stripLeading().matches("(?is)^(MATCH|OPTIONAL\\s+MATCH|WITH|RETURN)\\b.*"))
            throw new IllegalArgumentException("A read-only Cypher query is required");
        // Conservative supported subset; procedures and multiple statements are deliberately excluded.
        String code = query.replaceAll("'([^'\\\\]|\\\\.|'')*'|\"([^\"\\\\]|\\\\.)*\"|`[^`]*`", " ");
        if (code.matches("(?is).*\\b(CREATE|MERGE|SET|DELETE|REMOVE|DROP|CALL|LOAD|FOREACH|INSERT)\\b.*")
            || query.contains(";") || query.contains("//") || query.contains("/*"))
            throw new IllegalArgumentException("Cypher query contains unsupported or write clauses");
        database(d);
    }
    private String database(CapabilityDefinition d) {
        String name = String.valueOf(d.options().getOrDefault("database", "neo4j"));
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid Neo4j database");
        return name;
    }
    @Override public QueryResult execute(CapabilityDefinition d, Map<String, Object> parameters) throws Exception {
        HttpEndpointConfig connection = connections.get(d.connectionId(), type(), true);
        Map<String, Object> statement = Map.of("statement", "CALL { " + d.query() + " } RETURN * LIMIT " + (d.maxRows() + 1), "parameters", parameters,
            "resultDataContents", List.of("row", "graph"));
        JsonNode response = http.post(connection, "/db/" + database(d) + "/tx/commit",
            Map.of("statements", List.of(statement)), d.timeoutSeconds(), Map.of("max-execution-time", String.valueOf(d.timeoutSeconds() * 1000)));
        if (response.path("errors").size() > 0) throw new IllegalStateException(response.path("errors").get(0).path("message").asText("Graph query failed"));
        if (!response.path("results").isArray() || response.path("results").isEmpty())
            throw new IllegalStateException("Invalid Neo4j query response");
        JsonNode result = response.path("results").get(0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : result.path("data")) {
            if (rows.size() == d.maxRows()) break;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < result.path("columns").size(); i++)
                row.put(result.path("columns").get(i).asText(), json.convertValue(item.path("row").path(i), Object.class));
            if (item.has("graph")) row.put("_graph", json.convertValue(item.get("graph"), Object.class));
            rows.add(row);
        }
        return new QueryResult(rows, result.path("data").size() > d.maxRows(), Map.of());
    }
}
