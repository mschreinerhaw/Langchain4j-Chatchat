package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.datacapability.connection.QueryHttpClient;
import com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Fixed internal metadata procedures are separate from the business Cypher validator. */
@Component
@RequiredArgsConstructor
public class Neo4jMetadataCollector implements MetadataCollector {
    private final QueryHttpClient http;
    private static final List<String> QUERIES = List.of(
        "CALL db.labels() YIELD label RETURN label LIMIT 20001",
        "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType LIMIT 20001",
        "CALL db.schema.nodeTypeProperties() YIELD nodeType, nodeLabels, propertyName, propertyTypes, mandatory RETURN nodeType, nodeLabels, propertyName, propertyTypes, mandatory LIMIT 50001",
        "CALL db.schema.relTypeProperties() YIELD relType, propertyName, propertyTypes, mandatory RETURN relType, propertyName, propertyTypes, mandatory LIMIT 50001",
        "SHOW INDEXES YIELD * RETURN * LIMIT 20001",
        "SHOW CONSTRAINTS YIELD * RETURN * LIMIT 20001",
        "CALL db.schema.visualization() YIELD nodes, relationships RETURN nodes, relationships"
    );

    @Override public boolean supports(String type) { return "neo4j".equals(type); }

    @Override public List<MetadataObject> collect(SqlDatasourceConfig asset, List<String> databases) throws Exception {
        if (databases.isEmpty()) throw new IllegalArgumentException("请配置 Neo4j Database");
        var connection = NativeQueryDatasource.connection(asset);
        List<MetadataObject> objects = new ArrayList<>();
        int fieldCount = 0;
        for (String database : databases) {
            if (!database.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("无效的 Neo4j Database: " + database);
            JsonNode response = http.post(connection, "/db/" + SearchEngineMetadataCollector.segment(database) + "/tx/commit",
                Map.of("statements", QUERIES.stream().map(query -> Map.of("statement", query, "resultDataContents", List.of("row", "graph"))).toList()),
                Math.max(1, Math.min(60, asset.getDefaultTimeoutSeconds())), Map.of());
            SearchEngineMetadataCollector.requireObject(response);
            if (!response.path("errors").isArray() || !response.path("errors").isEmpty())
                throw new IllegalStateException("Neo4j 元数据采集失败: " + response.path("errors"));
            JsonNode results = response.path("results");
            if (!results.isArray() || results.size() != QUERIES.size()) throw new IllegalStateException("Neo4j 元数据返回不完整");
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                int limit = i == 2 || i == 3 ? 50000 : 20000;
                if (results.get(i).path("data").size() > limit) throw new IllegalStateException("Neo4j 元数据超过限制，请缩小采集范围");
                rows.add(rows(results.get(i)));
            }
            Map<String, List<MetadataObject.Field>> nodes = new LinkedHashMap<>(), relations = new LinkedHashMap<>();
            for (Map<String, Object> row : rows.get(0)) nodes.put(String.valueOf(row.get("label")), new ArrayList<>());
            for (Map<String, Object> row : rows.get(1)) relations.put(String.valueOf(row.get("relationshipType")), new ArrayList<>());
            for (Map<String, Object> definition : java.util.stream.Stream.concat(rows.get(4).stream(), rows.get(5).stream()).toList()) {
                Map<String, List<MetadataObject.Field>> group = "RELATIONSHIP".equals(definition.get("entityType")) ? relations : nodes;
                strings(definition.get("labelsOrTypes")).forEach(label -> group.computeIfAbsent(label, ignored -> new ArrayList<>()));
            }
            for (Map<String, Object> row : rows.get(2)) {
                for (String label : strings(row.get("nodeLabels"))) {
                    var fields = nodes.computeIfAbsent(label, ignored -> new ArrayList<>());
                    addProperty(fields, row);
                }
            }
            for (Map<String, Object> row : rows.get(3)) {
                String raw = String.valueOf(row.get("relType"));
                String type = raw.startsWith(":") ? raw.substring(1) : raw;
                if (type.startsWith("`") && type.endsWith("`")) type = type.substring(1, type.length() - 1).replace("``", "`");
                addProperty(relations.computeIfAbsent(type, ignored -> new ArrayList<>()), row);
            }
            Map<String, List<Map<String, Object>>> topology = topology(results.get(6));
            for (var group : List.of(nodes, relations)) {
                String kind = group == nodes ? "NODE_LABEL" : "RELATIONSHIP_TYPE";
                for (var entry : group.entrySet()) {
                    fieldCount += entry.getValue().size();
                    if (fieldCount > 50000 || objects.size() >= 20000) throw new IllegalStateException("Neo4j 元数据超过限制，请缩小采集范围");
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("source", "neo4j_schema_procedures");
                    attributes.put("propertySchema", "observed");
                    attributes.put("indexes", definitions(rows.get(4), entry.getKey(), kind));
                    attributes.put("constraints", definitions(rows.get(5), entry.getKey(), kind));
                    if ("RELATIONSHIP_TYPE".equals(kind)) attributes.put("topology", topology.getOrDefault(entry.getKey(), List.of()));
                    objects.add(new MetadataObject(asset.getId(), "neo4j", kind, database, entry.getKey(),
                        List.of(database, kind, entry.getKey()), "", entry.getValue(), attributes));
                }
            }
        }
        return objects;
    }

    private void addProperty(List<MetadataObject.Field> fields, Map<String, Object> row) {
        Object property = row.get("propertyName");
        if (property == null) return;
        String name = String.valueOf(property);
        // A mandatory flag observed for one label combination is not a declared constraint.
        Map<String, Object> evidence = new LinkedHashMap<>();
        row.forEach((key, value) -> { if (value != null) evidence.put(key, value); });
        evidence.put("source", "observed");
        fields.add(new MetadataObject.Field(name, List.of(name), String.join("|", strings(row.get("propertyTypes"))),
            null, "", evidence));
    }

    private Map<String, List<Map<String, Object>>> topology(JsonNode result) {
        Map<String, List<Map<String, Object>>> paths = new LinkedHashMap<>();
        for (JsonNode data : result.path("data")) {
            JsonNode graph = data.path("graph");
            if (!graph.path("nodes").isArray() || !graph.path("relationships").isArray())
                throw new IllegalStateException("Neo4j 拓扑元数据返回不完整");
            if (graph.path("nodes").size() > 20000 || graph.path("relationships").size() > 20000)
                throw new IllegalStateException("Neo4j 拓扑元数据超过限制");
            Map<String, Object> labels = new HashMap<>();
            for (JsonNode node : graph.path("nodes")) labels.put(node.path("id").asText(), SearchEngineMetadataCollector.plain(node.path("labels")));
            for (JsonNode relationship : graph.path("relationships")) {
                paths.computeIfAbsent(relationship.path("type").asText(), ignored -> new ArrayList<>()).add(Map.of("startLabels", labels.getOrDefault(relationship.path("startNode").asText(), List.of()),
                    "endLabels", labels.getOrDefault(relationship.path("endNode").asText(), List.of()),
                    "source", "schema_statistics", "mayIncludeUnobservedRelationships", true));
            }
        }
        paths.replaceAll((type, values) -> values.stream().distinct().sorted(Comparator.comparing(Map::toString)).toList());
        return paths;
    }

    private List<Map<String, Object>> definitions(List<Map<String, Object>> rows, String label, String kind) {
        return rows.stream().filter(row -> strings(row.get("labelsOrTypes")).contains(label))
            .filter(row -> ("RELATIONSHIP_TYPE".equals(kind) ? "RELATIONSHIP" : "NODE").equals(row.get("entityType")))
            .map(row -> {
                Map<String, Object> definition = new LinkedHashMap<>();
                for (String key : List.of("name", "type", "entityType", "labelsOrTypes", "properties", "indexProvider", "options", "propertyType"))
                    if (row.get(key) != null) definition.put(key, row.get(key));
                return definition;
            }).sorted(Comparator.comparing(row -> String.valueOf(row.get("name")))).toList();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private List<Map<String, Object>> rows(JsonNode result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!result.path("columns").isArray() || !result.path("data").isArray())
            throw new IllegalStateException("Neo4j 元数据结果结构异常");
        for (JsonNode data : result.path("data")) {
            Map<String, Object> row = new LinkedHashMap<>();
            JsonNode values = data.path("row"), columns = result.path("columns");
            if (!values.isArray() || !columns.isArray() || values.size() != columns.size())
                throw new IllegalStateException("Neo4j 元数据行结构异常");
            for (int i = 0; i < columns.size(); i++) row.put(columns.get(i).asText(),
                values.get(i).isNull() ? null : SearchEngineMetadataCollector.plain(values.get(i)));
            rows.add(row);
        }
        return rows;
    }
}
