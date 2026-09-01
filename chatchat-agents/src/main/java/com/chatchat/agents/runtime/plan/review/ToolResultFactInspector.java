package com.chatchat.agents.runtime.plan.review;

import com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Extracts transport-neutral structural facts from arbitrary tool results. */
public final class ToolResultFactInspector {
    private final ToolProtocolPayloadNavigator payloads;

    public ToolResultFactInspector(ToolProtocolPayloadNavigator payloads) {
        this.payloads = payloads;
    }

    public boolean hasStructuredEvidence(Object output) {
        return hasStructuredEvidence(output, 0);
    }

    public int structuredObservationCount(Object output) {
        Object normalized = payloads.normalize(output);
        if (normalized != output) return structuredObservationCount(normalized);
        if (!(output instanceof Map<?, ?> map)) return 0;
        Integer count = integer(first(map, "structuredObservationCount", "structured_observation_count"));
        if (count != null && count > 0) return count;
        Object observations = first(map, "structuredObservations", "structured_observations", "observations", "rows");
        if (observations instanceof Collection<?> collection && !collection.isEmpty()) return collection.size();
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            int nested = structuredObservationCount(first(map, key));
            if (nested > 0) return nested;
        }
        return 0;
    }

    public String discoveryResultCode(Object output) {
        return discoveryResultCode(output, 0);
    }

    public int discoveredCount(Object output, String listKey) {
        return discoveredCount(output, listKey, 0);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> enterpriseMetadataResult(Object output) {
        return enterpriseMetadataResult(output, 0);
    }

    public int sqlColumnMetadataCount(Object output) {
        return sqlColumnMetadataCount(output, 0);
    }

    private boolean hasStructuredEvidence(Object output, int depth) {
        if (output == null || depth > 8) return false;
        Object normalized = payloads.normalize(output);
        if (normalized != output) return hasStructuredEvidence(normalized, depth + 1);
        if (output instanceof List<?> list) return !list.isEmpty();
        if (!(output instanceof Map<?, ?> map) || map.isEmpty()) return false;
        Boolean success = bool(first(map, "success"));
        if (Boolean.TRUE.equals(success) && hasAnyKey(map, "rows", "columns", "results", "resultSets",
            "result_sets", "payload", "data", "operation", "analysisContext")) return true;
        Integer rowCount = integer(first(map, "rowCount", "row_count", "resultSetCount", "result_set_count",
            "statementCount", "statement_count"));
        if (rowCount != null && rowCount > 0) return true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).replace("_", "")
                .toLowerCase(Locale.ROOT);
            Object value = entry.getValue();
            Integer count = integer(value);
            if (count != null && count > 0 && ("count".equals(key) || "size".equals(key)
                || "total".equals(key) || key.endsWith("count") || key.endsWith("size") || key.endsWith("total"))) {
                return true;
            }
            if (value instanceof Collection<?> collection && !collection.isEmpty()) return true;
            if (value instanceof Map<?, ?> nested && !nested.isEmpty() && hasStructuredEvidence(nested, depth + 1)) return true;
        }
        for (String key : List.of("routingProjection", "structuredContent", "structured_content", "data",
            "result", "payload", "body", "output")) {
            if (hasStructuredEvidence(first(map, key), depth + 1)) return true;
        }
        return false;
    }

    private String discoveryResultCode(Object output, int depth) {
        if (output == null || depth > 6) return null;
        Object normalized = payloads.normalize(output);
        if (normalized != output) return discoveryResultCode(normalized, depth + 1);
        if (!(output instanceof Map<?, ?> map)) return null;
        Object direct = first(map, "resultCode", "result_code", "code");
        if (direct != null && !String.valueOf(direct).isBlank()) return String.valueOf(direct).trim();
        for (String key : List.of("retrievalReview", "retrieval_review", "structuredContent", "structured_content",
            "routingProjection", "preview", "data", "result", "payload", "body", "output")) {
            String nested = discoveryResultCode(first(map, key), depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enterpriseMetadataResult(Object output, int depth) {
        if (output == null || depth > 8) return Map.of();
        Object normalized = payloads.normalize(output);
        if (normalized != output) return enterpriseMetadataResult(normalized, depth + 1);
        if (!(output instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
        if ("enterprise_metadata_field_discovery.v1".equals(string(map.get("schemaVersion")))) return map;
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            Map<String, Object> nested = enterpriseMetadataResult(map.get(key), depth + 1);
            if (!nested.isEmpty()) return nested;
        }
        return Map.of();
    }

    private int sqlColumnMetadataCount(Object output, int depth) {
        if (output == null || depth > 8) return 0;
        Object normalized = payloads.normalize(output);
        if (normalized != output) return sqlColumnMetadataCount(normalized, depth + 1);
        if (output instanceof List<?> list) return looksLikeColumnRows(list) ? list.size() : 0;
        if (!(output instanceof Map<?, ?> map)) return 0;
        Object rows = first(map, "rows", "dataRows", "data_rows");
        if (rows instanceof List<?> list && looksLikeColumnRows(list)) return list.size();
        int searchCount = metadataSearchColumnCount(map);
        if (searchCount > 0) return searchCount;
        Integer rowCount = integer(first(map, "rowCount", "row_count", "returnedCount", "returned_count"));
        if (rowCount != null && rowCount > 0 && looksLikeColumnNames(first(map, "columns"))) return rowCount;
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output", "content")) {
            int nested = sqlColumnMetadataCount(first(map, key), depth + 1);
            if (nested > 0) return nested;
        }
        return 0;
    }

    private int metadataSearchColumnCount(Map<?, ?> map) {
        Object results = first(map, "results", "items", "records");
        if (results instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> nested) {
                int count = metadataSearchColumnCount(nested);
                if (count > 0) return count;
            }
        }
        Object columns = first(map, "columns");
        if (columns instanceof List<?> list && list.stream().anyMatch(this::looksLikeMetadataColumn)) return list.size();
        Integer count = integer(first(map, "columnCount", "column_count"));
        return count == null ? 0 : Math.max(0, count);
    }

    private int discoveredCount(Object output, String listKey, int depth) {
        if (output == null || depth > 6) return 0;
        Object normalized = payloads.normalize(output);
        if (normalized != output) return discoveredCount(normalized, listKey, depth + 1);
        if (!(output instanceof Map<?, ?> map)) return output instanceof List<?> list ? list.size() : 0;
        int explicit = discoveryValueCount(first(map, listKey), listKey);
        if (explicit > 0) return explicit;
        for (String key : List.of("selectedAsset", "selected_asset", "selected", "asset", "template")) {
            int selected = discoveryValueCount(first(map, key), listKey);
            if (selected > 0) return selected;
        }
        if ("templates".equals(listKey)) {
            int nested = associatedTemplateCount(map, depth + 1);
            if (nested > 0) return nested;
        }
        Integer count = integer(first(map, "returnedCount", "returned_count", "count"));
        if (count != null) return Math.max(0, count);
        for (String key : List.of(listKey, "routingProjection", "preview", "structuredContent", "structured_content",
            "data", "result", "payload", "body", "output", "content")) {
            int nested = discoveredCount(first(map, key), listKey, depth + 1);
            if (nested > 0) return nested;
        }
        return 0;
    }

    private int associatedTemplateCount(Object value, int depth) {
        if (value == null || depth > 6) return 0;
        Object normalized = payloads.normalize(value);
        if (normalized != value) return associatedTemplateCount(normalized, depth + 1);
        if (value instanceof List<?> list) return list.stream().mapToInt(item -> associatedTemplateCount(item, depth + 1)).sum();
        if (!(value instanceof Map<?, ?> map)) return 0;
        for (String key : List.of("associatedTemplates", "associated_templates", "sqlTemplates", "sql_templates")) {
            int count = discoveryValueCount(first(map, key), "templates");
            if (count > 0) return count;
        }
        for (String key : List.of("results", "items", "hits", "candidates", "data", "result", "payload")) {
            int count = associatedTemplateCount(first(map, key), depth + 1);
            if (count > 0) return count;
        }
        return 0;
    }

    private int discoveryValueCount(Object value, String listKey) {
        if (value instanceof List<?> list) return (int) list.stream().filter(item -> looksLikeDiscoveryItem(item, listKey)).count();
        return looksLikeDiscoveryItem(value, listKey) ? 1 : 0;
    }

    private boolean looksLikeDiscoveryItem(Object value, String key) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return false;
        if ("assets".equals(key)) {
            Object nested = first(map, "asset", "datasource", "target");
            return nested instanceof Map<?, ?> nestedMap && looksLikeDiscoveryItem(nestedMap, key)
                || first(map, "name", "assetName", "asset_name", "displayName", "toolName", "tool_name", "id") != null
                && first(map, "environment", "env", "databaseType", "database_type", "toolName", "tool_name", "id") != null;
        }
        if ("templates".equals(key)) return first(map, "templateId", "template_id", "id", "code", "name") != null;
        return true;
    }

    private boolean looksLikeMetadataColumn(Object value) {
        return value instanceof Map<?, ?> map && first(map, "name", "columnName", "column_name", "COLUMN_NAME") != null
            && first(map, "columnType", "dataType", "type", "column_type", "COLUMN_TYPE", "DATA_TYPE", "data_type") != null;
    }

    private boolean looksLikeColumnRows(List<?> rows) {
        return rows != null && !rows.isEmpty() && rows.stream().anyMatch(row -> row instanceof Map<?, ?> map
            && first(map, "COLUMN_NAME", "column_name") != null
            && first(map, "COLUMN_TYPE", "column_type", "DATA_TYPE", "data_type") != null);
    }

    private boolean looksLikeColumnNames(Object columns) {
        if (!(columns instanceof List<?> list) || list.isEmpty()) return false;
        Set<String> names = list.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
            .map(value -> value.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return names.contains("column_name") && (names.contains("column_type") || names.contains("data_type"));
    }

    private boolean hasAnyKey(Map<?, ?> map, String... keys) {
        for (String key : keys) if (first(map, key) != null) return true;
        return false;
    }

    private Object first(Map<?, ?> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
}
