package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.datacapability.connection.QueryHttpClient;
import com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@RequiredArgsConstructor
public class SearchEngineMetadataCollector implements MetadataCollector {
    private final QueryHttpClient http;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override public boolean supports(String type) { return Set.of("opensearch", "elasticsearch").contains(type); }

    @Override public List<MetadataObject> collect(SqlDatasourceConfig asset, List<String> scopes) throws Exception {
        if (scopes.isEmpty()) throw new IllegalArgumentException("请配置需要索引的索引名或别名；多个用逗号分隔");
        var connection = NativeQueryDatasource.connection(asset);
        int timeout = Math.max(1, Math.min(60, asset.getDefaultTimeoutSeconds()));
        Map<String, MetadataObject> objects = new LinkedHashMap<>();
        int fieldCount = 0;
        for (String scope : scopes) {
            if (!scope.matches("[A-Za-z0-9_*.-]+") || scope.equals("_all"))
                throw new IllegalArgumentException("无效的元数据索引范围: " + scope);
            String selected = segment(scope);
            JsonNode mappings = http.get(connection, "/" + selected + "/_mapping?expand_wildcards=open&allow_no_indices=false", timeout);
            requireObject(mappings);
            JsonNode aliases = http.get(connection, "/" + selected + "/_alias", timeout);
            requireObject(aliases);
            for (var iterator = mappings.fields(); iterator.hasNext();) {
                var entry = iterator.next();
                String index = entry.getKey();
                // Wildcards do not opt an asset into system indices.
                if (index.startsWith(".") && !scope.startsWith(".")) continue;
                if (objects.containsKey(index)) continue;
                if (objects.size() >= 20000) throw new IllegalStateException("元数据对象超过 20000，请缩小索引范围");
                JsonNode caps = http.get(connection, "/" + segment(index) + "/_field_caps?fields=*&include_unmapped=true", timeout);
                requireObject(caps);
                JsonNode mapping = entry.getValue().path("mappings");
                if (!mapping.isObject() || !caps.path("fields").isObject() || !aliases.path(index).path("aliases").isObject())
                    throw new IllegalStateException("元数据 Mapping、别名或字段能力返回不完整");
                if (!mapping.has("properties")) {
                    for (var mappingEntries = mapping.fields(); mappingEntries.hasNext();)
                        if (mappingEntries.next().getValue().has("properties"))
                            throw new IllegalStateException("旧版 typed Mapping 不受支持，请使用 Elasticsearch 7+ 或 OpenSearch");
                }
                List<MetadataObject.Field> fields = new ArrayList<>();
                flatten(mapping.path("properties"), List.of(), false, fields, caps.path("fields"));
                flatten(mapping.path("runtime"), List.of(), true, fields, caps.path("fields"));
                fieldCount += fields.size();
                if (fieldCount > 50000) throw new IllegalStateException("元数据字段超过 50000，请缩小索引范围");
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("source", "mapping_and_field_caps");
                attributes.put("requestedScope", scope);
                attributes.put("aliases", names(aliases.path(index).path("aliases")));
                attributes.put("mappingMeta", plain(mapping.path("_meta")));
                attributes.put("dynamic", plain(mapping.path("dynamic")));
                attributes.put("dynamicTemplates", plain(mapping.path("dynamic_templates")));
                String description = mapping.path("_meta").path("description").asText("");
                objects.put(index, new MetadataObject(asset.getId(), NativeQueryDatasource.type(asset), "INDEX",
                    index, index, List.of(index), description, fields, attributes));
            }
        }
        return List.copyOf(objects.values());
    }

    private void flatten(JsonNode properties, List<String> parent, boolean runtime,
                         List<MetadataObject.Field> fields, JsonNode capabilities) {
        if (parent.size() > 64) throw new IllegalStateException("元数据字段嵌套超过 64 层");
        for (var iterator = properties.fields(); iterator.hasNext();) {
            var entry = iterator.next();
            List<String> path = new ArrayList<>(parent); path.add(entry.getKey());
            String name = String.join(".", path);
            JsonNode mapping = entry.getValue();
            String type = mapping.path("type").asText(mapping.has("properties") ? "object" : "unknown");
            Map<String, Object> attributes = new LinkedHashMap<>();
            var ownMapping = mapping.deepCopy();
            if (ownMapping.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) ownMapping).remove(List.of("properties", "fields", "script"));
            attributes.put("mapping", plain(ownMapping));
            attributes.put("runtime", runtime);
            attributes.put("capabilities", plain(capabilities.path(name)));
            // Field caps are authoritative; unknown capability is kept unknown.
            JsonNode caps = capabilities.path(name).path(type);
            if (caps.has("searchable")) attributes.put("searchable", caps.path("searchable").asBoolean());
            if (caps.has("aggregatable")) attributes.put("aggregatable", caps.path("aggregatable").asBoolean());
            fields.add(new MetadataObject.Field(name, path, type, null,
                mapping.path("meta").path("description").asText(""), attributes));
            if (fields.size() > 50000) throw new IllegalStateException("元数据字段超过 50000，请缩小索引范围");
            flatten(mapping.path("properties"), path, runtime, fields, capabilities);
            flatten(mapping.path("fields"), path, runtime, fields, capabilities);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> values = new ArrayList<>(); node.fieldNames().forEachRemaining(values::add); return values;
    }

    static Object plain(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return JSON.convertValue(node, Object.class);
    }

    static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    static void requireObject(JsonNode response) {
        if (response == null || !response.isObject() || response.has("error"))
            throw new IllegalStateException("元数据接口返回异常");
    }
}
