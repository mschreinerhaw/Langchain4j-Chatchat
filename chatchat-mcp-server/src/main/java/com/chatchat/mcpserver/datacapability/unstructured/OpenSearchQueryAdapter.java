package com.chatchat.mcpserver.datacapability.unstructured;

import com.chatchat.common.retrieval.query.SearchQueryOptimizer;

import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class OpenSearchQueryAdapter implements CapabilityAdapter {
    private SearchQueryOptimizer queryOptimizer = new SearchQueryOptimizer();
    @org.springframework.beans.factory.annotation.Autowired
    public void setQueryOptimizer(SearchQueryOptimizer optimizer) { queryOptimizer.close(); queryOptimizer = optimizer; }

    private final QueryConnectionService connections;
    private final NativeDatabaseClient client;
    private final ObjectMapper json;
    @Override public CapabilityType type() { return CapabilityType.UNSTRUCTURED; }
    @Override public void validate(CapabilityDefinition d) {
        connections.get(d.connectionId(), type(), false);
        index(d);
        try {
            if (!json.readTree(d.query()).isObject()) throw new IllegalArgumentException("OpenSearch query must be a JSON object");
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid OpenSearch JSON query", ex); }
    }
    private String index(CapabilityDefinition d) {
        String name = String.valueOf(d.options().getOrDefault("index", ""));
        if (!name.matches("[a-z0-9][a-z0-9_.-]{0,254}") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("An explicit OpenSearch index is required");
        return name;
    }
    @Override public QueryResult execute(CapabilityDefinition d, Map<String, Object> parameters) throws Exception {
        ObjectNode body = (ObjectNode) QueryTemplates.json(json.readTree(d.query()), parameters, json);
        body.put("size", d.maxRows()); body.put("timeout", d.timeoutSeconds() + "s");
        @SuppressWarnings("unchecked") Map<String,Object> query = json.convertValue(body, Map.class);
        // A published DSL is validated, never rewritten into a different query.
        JsonNode response = json.valueToTree(queryOptimizer.search(query,
            SearchQueryOptimizer.Mode.DSL, (prepared, timeout) -> {
                JsonNode returned = client.post(connections.get(d.connectionId(), type(), true),
                    "/" + index(d) + "/_search", json.valueToTree(prepared), Math.max(1, (timeout + 999) / 1000), Map.of());
                return json.convertValue(returned, Map.class);
            }));
        if (response.has("error") || response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() > 0)
            throw new IllegalStateException("OpenSearch query failed, timed out or returned partial shard results");
        if (!response.path("hits").path("hits").isArray()) throw new IllegalStateException("Invalid OpenSearch response");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            if (rows.size() >= d.maxRows()) break;
            Map<String, Object> row = new LinkedHashMap<>();
            if (hit.path("_source").isObject()) hit.path("_source").fields().forEachRemaining(e -> row.put(e.getKey(), json.convertValue(e.getValue(), Object.class)));
            row.put("_id", hit.path("_id").asText()); row.put("_score", json.convertValue(hit.path("_score"), Object.class));
            rows.add(row);
        }
        JsonNode total = response.path("hits").path("total");
        long count = total.isNumber() ? total.asLong() : total.path("value").asLong(rows.size());
        return new QueryResult(rows, count > rows.size() || "gte".equals(total.path("relation").asText()), Map.of("total", count));
    }
}
