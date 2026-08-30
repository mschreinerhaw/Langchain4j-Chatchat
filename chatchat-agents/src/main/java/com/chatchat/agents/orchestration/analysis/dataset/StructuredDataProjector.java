package com.chatchat.agents.orchestration.analysis.dataset;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds analyzable record sets in arbitrary structured tool output.
 *
 * <p>The projection is intentionally schema- and domain-neutral. Collection names such as
 * {@code node}, {@code app}, or {@code queueInfos} are not special: every collection of objects
 * is a dataset, and every non-collection object containing scalar facts is a singleton dataset.
 * Nested scalar objects inside a collection row are flattened into that row, while nested object
 * collections become independent datasets. This prevents the same response subtree from being
 * analyzed once as a large parent row and again as many singleton child datasets.
 * This keeps nested HTTP JSON usable by the governed analysis pipeline without teaching Runtime
 * product-specific response fields.</p>
 */
public final class StructuredDataProjector {

    private static final int MAX_DEPTH = 16;
    private static final Set<String> RAW_DUPLICATE_FIELDS = Set.of(
        "rawBody", "raw_body", "rawPayload", "raw_payload"
    );

    private final Gson gson = new Gson();

    public List<Dataset> project(Object output) {
        return project(output, false);
    }

    public List<Dataset> projectForAnalysis(Object output) {
        return project(output, true);
    }

    private List<Dataset> project(Object output, boolean includeSingletonObjects) {
        Root root = payloadRoot(normalizeJsonString(output));
        List<Dataset> datasets = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        collect(root.value(), root.path(), false,
            includeSingletonObjects || root.singletonObjectsAreDatasets(),
            0, datasets, identities);
        return coalesce(datasets);
    }

    private Root payloadRoot(Object output) {
        Map<String, Object> root = asMap(output);
        if (root.isEmpty()) {
            return new Root("$", output, false);
        }
        Map<String, Object> data = asMap(normalizeJsonString(root.get("data")));
        if (!data.isEmpty()) {
            Object body = firstPresent(data, "body", "parsedBody", "responseBody");
            if (body != null) {
                return new Root("$.data.body", normalizeJsonString(body), true);
            }
            Object rawBody = firstPresent(data, "rawBody", "raw_body");
            Object decodedRawBody = normalizeJsonString(rawBody);
            if (decodedRawBody instanceof Map<?, ?> || decodedRawBody instanceof Collection<?>) {
                return new Root("$.data.rawBody", decodedRawBody, true);
            }
        }
        Object body = firstPresent(root, "body", "parsedBody", "responseBody");
        if (body != null) {
            return new Root("$.body", normalizeJsonString(body), true);
        }
        return new Root("$", output, false);
    }

    private void collect(Object value,
                         String path,
                         boolean collectionRow,
                         boolean singletonObjectsAreDatasets,
                         int depth,
                         List<Dataset> datasets,
                         Set<String> identities) {
        if (value == null || depth > MAX_DEPTH) {
            return;
        }
        Object normalized = normalizeJsonString(value);
        if (normalized instanceof Collection<?> collection) {
            List<Map<String, Object>> rows = objectRows(collection);
            if (!rows.isEmpty()) {
                addDataset(path, rows, datasets, identities);
            }
            int index = 0;
            for (Object item : collection) {
                Map<String, Object> originalRow = asMap(normalizeJsonString(item));
                if (!originalRow.isEmpty()) {
                    collect(originalRow, path + "[" + index + "]", true,
                        singletonObjectsAreDatasets, depth + 1, datasets, identities);
                }
                index++;
            }
            return;
        }
        Map<String, Object> map = asMap(normalized);
        if (!map.isEmpty()) {
            Map<String, Object> scalarFacts = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (RAW_DUPLICATE_FIELDS.contains(key)) {
                    continue;
                }
                Object child = normalizeJsonString(entry.getValue());
                String childPath = path + "." + key;
                if (scalarCollection(child)) {
                    scalarFacts.put(key, child);
                } else if (child instanceof Map<?, ?> || child instanceof Collection<?>) {
                    collect(child, childPath, collectionRow, singletonObjectsAreDatasets,
                        depth + 1, datasets, identities);
                } else if (scalar(child)) {
                    scalarFacts.put(key, child);
                }
            }
            if (singletonObjectsAreDatasets && !collectionRow && !scalarFacts.isEmpty()) {
                addDataset(path, List.of(Collections.unmodifiableMap(
                    new LinkedHashMap<>(scalarFacts))), datasets, identities);
            }
            return;
        }
    }

    private void addDataset(String path,
                            List<Map<String, Object>> rows,
                            List<Dataset> datasets,
                            Set<String> identities) {
        if (rows.isEmpty()) {
            return;
        }
        // The same values can legitimately be returned through more than one source path
        // (for example, a canonical structured-data member and a presentation result member).
        // Keep both locations so the analysis model does not lose data lineage.
        String identity = path + "\u0000" + rows;
        if (!identities.add(identity)) {
            return;
        }
        Set<String> columns = new LinkedHashSet<>();
        rows.forEach(row -> columns.addAll(row.keySet()));
        datasets.add(new Dataset(path, List.copyOf(rows), List.copyOf(columns)));
    }

    private List<Map<String, Object>> objectRows(Object value) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : collection) {
            Map<String, Object> row = asMap(normalizeJsonString(item));
            if (row.isEmpty()) {
                return List.of();
            }
            Map<String, Object> flattened = new LinkedHashMap<>();
            flattenRow(row, "", flattened, 0);
            if (flattened.isEmpty()) {
                return List.of();
            }
            rows.add(Collections.unmodifiableMap(flattened));
        }
        return List.copyOf(rows);
    }

    private void flattenRow(Map<String, Object> source,
                            String prefix,
                            Map<String, Object> target,
                            int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (RAW_DUPLICATE_FIELDS.contains(entry.getKey())) {
                continue;
            }
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object child = normalizeJsonString(entry.getValue());
            Map<String, Object> nested = asMap(child);
            if (!nested.isEmpty()) {
                flattenRow(nested, key, target, depth + 1);
            } else if (scalar(child) || scalarCollection(child)) {
                target.put(key, child);
            }
        }
    }

    private boolean scalarCollection(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return false;
        }
        return collection.stream().allMatch(this::scalar);
    }

    private List<Dataset> coalesce(List<Dataset> datasets) {
        Map<String, MutableDataset> merged = new LinkedHashMap<>();
        for (Dataset dataset : datasets) {
            String normalizedPath = dataset.path().replaceAll("\\[\\d+]", "[]");
            String key = normalizedPath + "\u0000" + dataset.columns();
            MutableDataset target = merged.computeIfAbsent(
                key, ignored -> new MutableDataset(normalizedPath, dataset.columns()));
            for (Map<String, Object> row : dataset.rows()) {
                target.rows.putIfAbsent(String.valueOf(row), row);
            }
        }
        return merged.values().stream()
            .map(item -> new Dataset(item.path, List.copyOf(item.rows.values()), item.columns))
            .toList();
    }

    private Object normalizeJsonString(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]")))) {
            return value;
        }
        try {
            return gson.fromJson(trimmed, Object.class);
        } catch (JsonParseException ignored) {
            return value;
        }
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private boolean scalar(Object value) {
        return value == null
            || value instanceof CharSequence
            || value instanceof Number
            || value instanceof Boolean
            || value instanceof Enum<?>;
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    public record Dataset(String path, List<Map<String, Object>> rows, List<String> columns) {
    }

    private static final class MutableDataset {
        private final String path;
        private final List<String> columns;
        private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        private MutableDataset(String path, List<String> columns) {
            this.path = path;
            this.columns = columns;
        }
    }

    private record Root(String path, Object value, boolean singletonObjectsAreDatasets) {
    }
}
