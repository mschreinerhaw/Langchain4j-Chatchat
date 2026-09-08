package com.chatchat.agents.orchestration.analysis.prompt;

import com.chatchat.agents.orchestration.analysis.contract.AnalysisContextPresentationContract;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Produces a deterministic, evidence-free capability snapshot before prompt synthesis.
 * It uses declared schema/capability metadata and dataset shape only; field names are
 * never interpreted as business vocabulary and raw record values are never inspected.
 */
final class AnalysisDataCapabilityProbe {
    static final String SCHEMA_VERSION = "analysis_data_capabilities.v1";
    private static final Set<String> NUMERIC_TYPES = Set.of(
        "byte", "short", "integer", "int", "long", "float", "double", "decimal", "number", "numeric");
    private static final Set<String> TIME_TYPES = Set.of(
        "date", "time", "datetime", "timestamp", "instant", "localdate", "localdatetime", "offsetdatetime");
    private static final Set<String> AGGREGATING_OPERATIONS = Set.of(
        "AGGREGATE", "SUM", "AVG", "AVERAGE", "COUNT", "MIN", "MAX", "SHARE", "CONTRIBUTION");
    private static final int MAX_DATASETS = 50;
    private static final int MAX_FIELDS_PER_DATASET = 40;

    Map<String, Object> probe(List<Dataset> datasets) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Set<String> allMethods = new LinkedHashSet<>();
        int datasetCount = datasets == null ? 0 : Math.min(MAX_DATASETS, datasets.size());
        int count = 0;
        for (Dataset dataset : datasets == null ? List.<Dataset>of() : datasets) {
            if (count++ >= MAX_DATASETS) break;
            Map<String, Object> snapshot = dataset(dataset);
            snapshots.add(snapshot);
            allMethods.addAll(strings(snapshot.get("supportedMethodology")));
        }
        allMethods.add("OBSERVE");
        if (datasetCount > 1) allMethods.add("CROSS_VALIDATE");
        return Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "probePolicy", "DECLARED_METADATA_AND_DATASET_SHAPE_ONLY",
            "rawValuesInspected", false,
            "datasets", List.copyOf(snapshots),
            "supportedMethodology", List.copyOf(allMethods));
    }

    private Map<String, Object> dataset(Dataset dataset) {
        Map<String, Object> context = dataset.analysisContext() == null ? Map.of() : dataset.analysisContext();
        Map<String, Object> semantic = AnalysisContextPresentationContract.semanticView(dataset.reference(), context);
        List<Map<String, Object>> fields = maps(semantic.get("fields"));
        List<String> numeric = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        List<String> time = new ArrayList<>();
        List<Map<String, Object>> declaredFields = new ArrayList<>();
        int fieldCount = 0;
        for (Map<String, Object> field : fields) {
            if (fieldCount++ >= MAX_FIELDS_PER_DATASET) break;
            String name = text(field.get("technicalName"));
            if (name.isBlank()) continue;
            String type = normalizedType(field.get("type"));
            String role = text(field.get("semanticRole")).toUpperCase(Locale.ROOT);
            String kind;
            if (TIME_TYPES.contains(type) || role.contains("TIME") || role.contains("TEMPORAL")) {
                kind = "TIME_DIMENSION";
                time.add(name);
                dimensions.add(name);
            } else if (NUMERIC_TYPES.contains(type) || role.contains("METRIC") || role.contains("MEASURE")) {
                kind = "NUMERIC_MEASURE";
                numeric.add(name);
            } else {
                kind = "DIMENSION_CANDIDATE";
                dimensions.add(name);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("technicalName", name);
            put(item, "displayName", field.get("displayName"));
            put(item, "type", field.get("type"));
            put(item, "unit", field.get("unit"));
            put(item, "semanticRole", field.get("semanticRole"));
            item.put("capabilityKind", kind);
            declaredFields.add(Map.copyOf(item));
        }

        Set<String> declaredOperations = new LinkedHashSet<>();
        collectValuesForKeys(context, Set.of("allowedOperations", "operations", "operation"), declaredOperations);
        Set<String> operations = new LinkedHashSet<>();
        declaredOperations.forEach(value -> operations.add(value.toUpperCase(Locale.ROOT)));
        boolean aggregatable = operations.stream().anyMatch(AGGREGATING_OPERATIONS::contains);
        int timePointCount = declaredTimePointCount(context);
        List<String> declaredBaselines = declaredBindings(context, Set.of(
            "baseline", "baselines", "comparisonBaseline", "historicalBaseline"));
        boolean explicitBaseline = !declaredBaselines.isEmpty();

        Set<String> methods = new LinkedHashSet<>();
        methods.add("OBSERVE");
        if (!dimensions.isEmpty()) methods.add("DECOMPOSE");
        if (!dimensions.isEmpty() && !numeric.isEmpty()) {
            methods.add("COMPARE");
            methods.add("RANK");
            methods.add("DISTRIBUTION");
        }
        if (aggregatable && !dimensions.isEmpty() && !numeric.isEmpty()) methods.add("CONTRIBUTION");
        if (explicitBaseline) methods.add("BASELINE");
        if (!time.isEmpty() && timePointCount >= 2 && !numeric.isEmpty()) methods.add("TREND");
        if (numeric.size() >= 2 && dataset.recordCount() >= 2) methods.add("CORRELATION");
        if (dataset.recordCount() > 0) {
            methods.add("EXPLAIN");
            methods.add("ASSESS_IMPACT");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetReference", dataset.reference());
        result.put("recordCount", dataset.recordCount());
        result.put("fields", List.copyOf(declaredFields));
        result.put("numericMeasures", List.copyOf(numeric));
        result.put("computableMetrics", aggregatable ? List.copyOf(numeric) : List.of());
        result.put("decomposableDimensions", List.copyOf(dimensions));
        result.put("timeDimensions", List.copyOf(time));
        result.put("hasTimeDimension", !time.isEmpty());
        result.put("timeSpan", Map.of(
            "declaredPointCount", timePointCount,
            "supportsMultiPeriod", timePointCount >= 2,
            "status", timePointCount > 0 ? "DECLARED" : "UNKNOWN"));
        result.put("hasHistoricalBaseline", explicitBaseline);
        result.put("declaredBaselines", declaredBaselines);
        result.put("declaredOperations", List.copyOf(operations));
        result.put("supportedMethodology", List.copyOf(methods));
        return Map.copyOf(result);
    }

    private int declaredTimePointCount(Object value) {
        int declared = integerForKeys(value, Set.of("timePointCount", "periodCount", "periodsCovered"));
        if (declared > 0) return declared;
        Object range = valueForKey(value, Set.of("timeRange", "timeScope"));
        if (range instanceof Map<?, ?> map) {
            String start = first(map, "start", "from", "begin");
            String end = first(map, "end", "to", "until");
            if (!start.isBlank() && !end.isBlank() && !start.equals(end)) return 2;
        }
        return 0;
    }

    private int integerForKeys(Object value, Set<String> keys) {
        Object candidate = valueForKey(value, keys);
        if (candidate instanceof Number number) return Math.max(0, number.intValue());
        try { return Math.max(0, Integer.parseInt(text(candidate))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private Object valueForKey(Object value, Set<String> keys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && keys.contains(String.valueOf(entry.getKey()))) return entry.getValue();
                Object nested = valueForKey(entry.getValue(), keys);
                if (nested != null) return nested;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Object nested = valueForKey(item, keys);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private List<String> declaredBindings(Object value, Set<String> keys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectBindings(value, keys, result);
        return result.stream().limit(8).toList();
    }

    private void collectBindings(Object value, Set<String> keys, Set<String> target) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null && keys.contains(String.valueOf(key))) {
                    String binding = compactBinding(item);
                    if (!binding.isBlank()) target.add(binding);
                }
                collectBindings(item, keys, target);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectBindings(item, keys, target));
        }
    }

    private String compactBinding(Object value) {
        if (value == null) return "";
        if (!(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) return text(value);
        String compact;
        try { compact = ModelProtocolJson.compact(value); }
        catch (RuntimeException invalid) { compact = text(value); }
        return compact.length() <= 320 ? compact : compact.substring(0, 320);
    }

    private void collectValuesForKeys(Object value, Set<String> keys, Set<String> target) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null && keys.contains(String.valueOf(key))) collectScalars(item, target);
                collectValuesForKeys(item, keys, target);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectValuesForKeys(item, keys, target));
        }
    }

    private void collectScalars(Object value, Set<String> target) {
        if (value instanceof Map<?, ?> map) map.values().forEach(item -> collectScalars(item, target));
        else if (value instanceof Iterable<?> iterable) iterable.forEach(item -> collectScalars(item, target));
        else if (!text(value).isBlank()) target.add(text(value));
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, nested) -> { if (key != null && nested != null) map.put(String.valueOf(key), nested); });
            result.add(map);
        }
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> { if (!text(item).isBlank()) result.add(text(item)); });
        return result;
    }

    private String first(Map<?, ?> map, String... keys) {
        for (String key : keys) if (!text(map.get(key)).isBlank()) return text(map.get(key));
        return "";
    }

    private String normalizedType(Object value) {
        String type = text(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String candidate : TIME_TYPES) if (type.contains(candidate)) return candidate;
        for (String candidate : NUMERIC_TYPES) if (type.contains(candidate)) return candidate;
        return type;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (!text(value).isBlank()) target.put(key, String.valueOf(value).trim());
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
