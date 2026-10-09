package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared technical metadata retrieval. Permissions and physical scopes are applied before BM25 ranking. */
@Service
@RequiredArgsConstructor
public class DatasourceMetadataSearchService {
    private final SqlDatasourceConfigService datasources;
    private final MetadataIndexService indexes;
    private final LuceneMcpSearchService lucene;
    private final ObjectMapper json;

    public Map<String, Object> search(Map<String, Object> request) {
        Map<String, Object> input = request == null ? Map.of() : request;
        String assetId = text(input.get("assetId")), assetName = text(input.get("assetName"));
        String type = text(input.getOrDefault("databaseType", input.get("dbType"))), environment = text(input.get("env"));
        String namespace = text(input.getOrDefault("namespace", input.getOrDefault("database", input.get("schema"))));
        String exact = text(input.getOrDefault("objectName", input.get("tableName")));
        String query = text(input.getOrDefault("query", input.get("q")));
        int limit = input.get("limit") instanceof Number n ? Math.max(1, Math.min(200, n.intValue())) : 20;
        boolean includeFields = !Boolean.FALSE.equals(input.getOrDefault("includeFields", input.get("includeColumns")));
        List<Candidate> candidates = new ArrayList<>();
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (SqlDatasourceConfig asset : datasources.listEnabled()) {
            if (assetId != null && !assetId.equals(asset.getId())) continue;
            if (assetName != null && !assetName.equals(asset.getName()) && !assetName.equals(asset.getTitle())) continue;
            if (type != null && !type.equalsIgnoreCase(asset.getDatabaseType())) continue;
            if (environment != null && !environment.equalsIgnoreCase(asset.getEnvironment())) continue;
            MetadataIndex index = indexes.indexFor(asset);
            statuses.add(status(index));
            for (MetadataObject object : visibleObjects(asset, index)) {
                if (namespace != null && !namespace.equals(object.namespace()) && !object.path().contains(namespace)) continue;
                if (exact != null && !exact.equals(object.name()) && !exact.equals(object.qualifiedName())) continue;
                candidates.add(new Candidate(asset, object));
            }
        }
        Map<String, Float> scores = lucene.rankScopedMetadata(candidates.stream().map(candidate -> assetDoc(candidate.asset(), candidate.object())).toList(), query);
        List<Candidate> ranked = candidates.stream().filter(candidate -> scores.containsKey(candidate.object().id()))
            .sorted(Comparator.<Candidate>comparingDouble(candidate -> scores.get(candidate.object().id())).reversed()
                .thenComparing(candidate -> candidate.object().id())).toList();
        List<Map<String, Object>> results = ranked.stream().limit(limit).map(candidate -> result(candidate.asset(), candidate.object(), includeFields, scores.get(candidate.object().id()))).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "datasource_metadata.v1"); response.put("success", true);
        response.put("totalMatched", ranked.size()); response.put("count", results.size()); response.put("truncated", ranked.size() > limit);
        response.put("results", results); response.put("metadataStatus", statuses);
        response.put("usage", "Use exact physical identifiers and native types. Neo4j observed properties are not declared constraints. This response does not execute queries.");
        return response;
    }

    /** Empty references fail closed for a template; they do not mean all objects. */
    public Map<String, Object> context(SqlDatasourceConfig asset, List<String> references, List<String> namespaces) {
        return context(asset, references, namespaces, 0);
    }

    public Map<String, Object> context(SqlDatasourceConfig asset, List<String> references, List<String> namespaces, long reviewedAtMs) {
        MetadataIndex index = indexes.indexFor(asset);
        List<MetadataObject> scoped = visibleObjects(asset, index).stream()
            .filter(object -> namespaces.isEmpty() || namespaces.contains(object.namespace())).toList();
        List<String> ambiguous = references.stream().filter(reference -> {
            List<MetadataObject> matches = scoped.stream().filter(object -> referenced(object, List.of(reference))).toList();
            return matches.size() > 1 && matches.stream().anyMatch(object -> !"INDEX".equals(object.kind()));
        }).toList();
        List<String> missing = references.stream().filter(reference -> scoped.stream().noneMatch(object -> referenced(object, List.of(reference)))).toList();
        List<Map<String, Object>> objects = scoped.stream()
            .filter(object -> referenced(object, references))
            .filter(object -> !referenced(object, ambiguous))
            .limit(20).map(object -> result(asset, object, true, 1F)).toList();
        Map<String, Object> result = new LinkedHashMap<>(status(index));
        result.put("references", references); result.put("objects", objects);
        long matchedCount = scoped.stream().filter(object -> referenced(object, references)).filter(object -> !referenced(object, ambiguous)).count();
        result.put("totalMatched", matchedCount); result.put("objectsTruncated", matchedCount > objects.size());
        result.put("missingReferences", missing); result.put("ambiguousReferences", ambiguous);
        result.put("scope", "template_references");
        List<MetadataIndex.Change> changes = index.changes().stream()
            .filter(change -> change.changedAtMs() > reviewedAtMs)
            .filter(change -> namespaces.isEmpty() || namespaces.contains(change.namespace()))
            .filter(change -> references.contains(change.name()) || references.contains(change.qualifiedName())).toList();
        result.put("changes", changes);
        result.put("reviewStatus", index.error() != null ? "METADATA_UNAVAILABLE" : references.isEmpty() || !ambiguous.isEmpty() ? "REFERENCE_SCOPE_REQUIRED"
            : !changes.isEmpty() ? "NEEDS_REVIEW" : !missing.isEmpty() || objects.isEmpty() ? "REFERENCE_NOT_INDEXED" : "AVAILABLE");
        return result;
    }

    public List<MetadataObject> visibleObjects(SqlDatasourceConfig asset, MetadataIndex index) {
        if (index.error() != null) return List.of();
        List<String> allowed = strings(asset.getAllowedTablesJson());
        List<String> sensitiveObjects = strings(asset.getSensitiveTablesJson()), sensitiveFields = strings(asset.getSensitiveFieldsJson());
        return index.objects().stream()
            .filter(object -> allowed.isEmpty() || referenced(object, allowed))
            .filter(object -> !referenced(object, sensitiveObjects))
            .map(object -> {
                MetadataObject visible = redact(object, sensitiveFields);
                if (allowed.isEmpty() && sensitiveObjects.isEmpty()) return visible;
                Map<String, Object> attributes = new LinkedHashMap<>(visible.attributes());
                attributes.remove("topology");
                return new MetadataObject(visible.datasourceId(), visible.databaseType(), visible.kind(), visible.namespace(), visible.name(), visible.path(), visible.description(), visible.fields(), attributes);
            }).toList();
    }

    private boolean matches(List<String> names, MetadataObject object) { return names.contains(object.name()) || names.contains(object.qualifiedName()); }

    private boolean referenced(MetadataObject object, List<String> names) {
        if (matches(names, object)) return true;
        return object.attributes().get("aliases") instanceof List<?> aliases && aliases.stream().anyMatch(names::contains);
    }

    private MetadataObject redact(MetadataObject object, List<String> sensitiveFields) {
        List<MetadataObject.Field> fields = object.fields().stream().filter(field -> sensitiveFields.stream().noneMatch(sensitive ->
            List.of(field.name(), object.name() + "." + field.name(), object.qualifiedName() + "." + field.name()).stream()
                .anyMatch(name -> name.equals(sensitive) || name.startsWith(sensitive + ".")))).toList();
        // Definitions can contain sensitive property names. Do not return them when fields were redacted.
        Map<String, Object> attributes = new LinkedHashMap<>(object.attributes());
        if (!sensitiveFields.isEmpty()) {
            attributes.remove("indexes"); attributes.remove("constraints"); attributes.remove("dynamicTemplates");
        }
        return new MetadataObject(object.datasourceId(), object.databaseType(), object.kind(), object.namespace(), object.name(), object.path(), object.description(), fields, attributes);
    }

    private List<String> strings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid metadata access scope", ex); }
    }

    private Map<String, Object> status(MetadataIndex index) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("datasourceId", index.datasourceId()); status.put("databaseType", index.databaseType());
        status.put("refreshedAtMs", index.error() == null ? index.refreshedAtMs() : 0); status.put("error", index.error());
        return status;
    }

    private Map<String, Object> result(SqlDatasourceConfig asset, MetadataObject object, boolean includeFields, float score) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", object.id()); value.put("assetId", asset.getId()); value.put("assetName", asset.getName());
        value.put("databaseType", object.databaseType()); value.put("kind", object.kind()); value.put("name", object.name());
        value.put("namespace", object.namespace()); value.put("path", object.path()); value.put("qualifiedName", object.qualifiedName());
        value.put("description", object.description()); value.put("attributes", object.attributes()); value.put("score", score);
        value.put("fieldCount", object.fields().size());
        if (includeFields) {
            value.put("fields", object.fields().stream().limit(200).toList());
            value.put("fieldsTruncated", object.fields().size() > 200);
        }
        return value;
    }

    public static LuceneMcpSearchService.AssetDoc assetDoc(SqlDatasourceConfig asset, MetadataObject object) {
        return new LuceneMcpSearchService.AssetDoc(object.id(), "sql_datasource", object.qualifiedName(), object.name(), asset.getToolName(),
            asset.getEnvironment(), object.databaseType(), List.of("metadata_object", object.kind().toLowerCase(Locale.ROOT)), "metadata_object",
            asset.getId(), object.namespace(), object.name(), object.qualifiedName(), object.indexText(), object.description(), asset.getDescription(), object.indexText());
    }

    private record Candidate(SqlDatasourceConfig asset, MetadataObject object) {}
    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim(); }
}
