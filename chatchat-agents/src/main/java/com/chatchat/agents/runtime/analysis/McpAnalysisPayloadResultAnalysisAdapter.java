package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.common.mcp.runtime.McpAnalysisPayload;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Projects the canonical governed data from a lossless MCP envelope.
 *
 * <p>{@code rawData} may mirror the same response through text and structured MCP representations.
 * It is therefore a recovery source only, never an additional analysis source.</p>
 */
final class McpAnalysisPayloadResultAnalysisAdapter implements RuntimeResultAnalysisAdapter {

    private static final int MAX_DEPTH = 12;
    private static final List<String> ENVELOPE_KEYS = List.of(
        "data", "result", "payload", "structuredContent", "structured_content");
    private static final List<String> BODY_KEYS = List.of(
        "body", "parsedBody", "responseBody", "response_body");
    private static final List<String> RECORD_KEYS = List.of("records", "rows", "results");
    private final Gson gson = new Gson();

    @Override
    public String id() {
        return "mcp_analysis_payload_records.v1";
    }

    @Override
    public int priority() {
        return 900;
    }

    @Override
    public boolean supports(AnalysisRequest request) {
        return McpAnalysisPayload.SCHEMA_VERSION.equals(
            string(map(request.payload()).get("schemaVersion")));
    }

    @Override
    public AnalysisResult adapt(AnalysisRequest request) {
        Map<String, Object> envelope = map(request.payload());
        Object governedData = envelope.get("data");
        List<AnalysisDataset> stdoutDatasets = new PythonStdoutRecordProjector().project(
            request.datasetReference(), governedData);
        if (!stdoutDatasets.isEmpty()) return new AnalysisResult(McpAnalysisPayload.SCHEMA_VERSION,
            "MCP_CANONICAL_BUSINESS_DATA", stdoutDatasets);
        boolean governedBodyPresent = hasCanonicalBody(governedData);
        List<Candidate> candidates = canonicalCandidates(governedData, "$.data");
        String source = "governed_data";
        if (candidates.isEmpty() && !governedBodyPresent) {
            candidates = canonicalCandidates(envelope.get("rawData"), "$.rawData");
            source = "raw_data_recovery";
        }
        List<AnalysisDataset> datasets = toDatasets(
            request.datasetReference(), candidates, source, analysisContext(governedData));
        if (datasets.isEmpty() && governedData != null && !governedBodyPresent) {
            datasets = List.of(canonicalPayloadDataset(request, governedData));
        }
        return new AnalysisResult(McpAnalysisPayload.SCHEMA_VERSION,
            "MCP_CANONICAL_BUSINESS_DATA", datasets);
    }

    private List<Candidate> canonicalCandidates(Object value, String path) {
        Object normalized = normalizeJson(value);
        List<Candidate> bodies = new ArrayList<>();
        findBodies(normalized, path, 0, bodies);
        if (!bodies.isEmpty()) {
            List<Candidate> records = new ArrayList<>();
            for (Candidate body : bodies) {
                collectBusinessCollections(body.value(), body.path(), 0, records);
            }
            if (!records.isEmpty()) return deduplicate(records);
        }
        List<Candidate> records = new ArrayList<>();
        findNamedRecords(normalized, path, 0, records);
        return deduplicate(records);
    }

    private boolean hasCanonicalBody(Object value) {
        List<Candidate> bodies = new ArrayList<>();
        findBodies(normalizeJson(value), "$.data", 0, bodies);
        return !bodies.isEmpty();
    }

    private void findBodies(Object value, String path, int depth, List<Candidate> bodies) {
        if (value == null || depth > MAX_DEPTH) return;
        Map<String, Object> current = map(normalizeJson(value));
        if (current.isEmpty()) return;
        for (String key : BODY_KEYS) {
            if (current.containsKey(key) && current.get(key) != null) {
                bodies.add(new Candidate(path + "." + key, normalizeJson(current.get(key)), List.of()));
            }
        }
        if (!bodies.isEmpty()) return;
        for (String key : ENVELOPE_KEYS) {
            if (current.containsKey(key)) {
                findBodies(current.get(key), path + "." + key, depth + 1, bodies);
                if (!bodies.isEmpty()) return;
            }
        }
    }

    private void findNamedRecords(Object value, String path, int depth, List<Candidate> records) {
        if (value == null || depth > MAX_DEPTH) return;
        Object normalized = normalizeJson(value);
        if (objectRows(normalized) != null) {
            records.add(new Candidate(path, normalized, List.of()));
            return;
        }
        Map<String, Object> current = map(normalized);
        if (current.isEmpty()) return;
        for (String key : RECORD_KEYS) {
            Object candidate = current.get(key);
            if (objectRows(candidate) != null) {
                records.add(new Candidate(path + "." + key, candidate, List.of()));
            }
        }
        if (!records.isEmpty()) return;
        for (String key : ENVELOPE_KEYS) {
            if (current.containsKey(key)) {
                findNamedRecords(current.get(key), path + "." + key, depth + 1, records);
                if (!records.isEmpty()) return;
            }
        }
    }

    private void collectBusinessCollections(Object value,
                                            String path,
                                            int depth,
                                            List<Candidate> records) {
        if (value == null || depth > MAX_DEPTH) return;
        Object normalized = normalizeJson(value);
        if (objectRows(normalized) != null) {
            records.add(new Candidate(path, normalized, List.of()));
            return;
        }
        Map<String, Object> current = map(normalized);
        if (current.isEmpty()) return;
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object child = normalizeJson(entry.getValue());
            String childPath = path + "." + entry.getKey();
            if (objectRows(child) != null) {
                records.add(new Candidate(childPath, child, List.of()));
            } else if (child instanceof Map<?, ?> || child instanceof Collection<?>) {
                collectBusinessCollections(child, childPath, depth + 1, records);
            }
        }
    }

    private List<Candidate> deduplicate(List<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> aliases = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            List<Map<String, Object>> rows = objectRows(candidate.value());
            if (rows == null || rows.isEmpty()) continue;
            String fingerprint = ModelProtocolJson.sha256Hex(ModelProtocolJson.compact(rows));
            unique.putIfAbsent(fingerprint, new Candidate(candidate.path(), rows, List.of()));
            aliases.computeIfAbsent(fingerprint, ignored -> new LinkedHashSet<>())
                .add(candidate.path());
        }
        List<Candidate> result = new ArrayList<>();
        unique.forEach((fingerprint, candidate) -> result.add(new Candidate(
            candidate.path(), candidate.value(), List.copyOf(aliases.get(fingerprint)))));
        return List.copyOf(result);
    }

    private List<AnalysisDataset> toDatasets(String reference,
                                             List<Candidate> candidates,
                                             String source,
                                             Map<String, Object> rootContext) {
        List<AnalysisDataset> datasets = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            List<Map<String, Object>> rows = objectRows(candidate.value());
            if (rows == null || rows.isEmpty()) continue;
            Map<String, Object> context = new LinkedHashMap<>(rootContext);
            context.put("projectionMode", "CANONICAL_MCP_DATA");
            context.put("canonicalPath", candidate.path());
            context.put("projectionSource", source);
            if (candidate.aliases().size() > 1) {
                context.put("sourceAliases", candidate.aliases());
            }
            context.values().removeIf(value -> value == null);
            String datasetReference = candidates.size() == 1
                ? reference : reference + "#dataset-" + (index + 1);
            datasets.add(new AnalysisDataset(datasetReference, context, rows));
        }
        return List.copyOf(datasets);
    }

    private AnalysisDataset canonicalPayloadDataset(AnalysisRequest request, Object governedData) {
        String content = ModelProtocolJson.compact(governedData);
        int chunkChars = Math.max(1_000, request.maximumRecordChars());
        List<Map<String, Object>> records = new ArrayList<>();
        for (int from = 0, index = 1; from < content.length(); from += chunkChars, index++) {
            int to = Math.min(content.length(), from + chunkChars);
            records.add(Map.of(
                "chunkIndex", index,
                "fromChar", from,
                "toChar", to,
                "sourceComplete", true,
                "content", content.substring(from, to)));
        }
        return new AnalysisDataset(request.datasetReference() + "#payload", Map.of(
            "projectionMode", "CANONICAL_MCP_PAYLOAD",
            "source", "governed_data",
            "analysisPolicy", Map.of("mode", "PRESERVE_ONLY")), records);
    }

    private Map<String, Object> analysisContext(Object governedData) {
        Map<String, Object> current = map(normalizeJson(governedData));
        Map<String, Object> context = map(current.get("analysisContext"));
        return context.isEmpty() ? Map.of() : context;
    }

    private List<Map<String, Object>> objectRows(Object value) {
        if (!(normalizeJson(value) instanceof Collection<?> collection) || collection.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : collection) {
            Map<String, Object> row = map(normalizeJson(item));
            if (row.isEmpty()) return null;
            // SQL NULL is valid business data. Map.copyOf rejects null values,
            // so retain them in an immutable null-tolerant row representation.
            rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return List.copyOf(rows);
    }

    private Object normalizeJson(Object value) {
        if (!(value instanceof String text)) return value;
        String trimmed = text.trim();
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]")))) return value;
        try {
            return gson.fromJson(trimmed, Object.class);
        } catch (JsonParseException ignored) {
            return value;
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Candidate(String path, Object value, List<String> aliases) { }
}
