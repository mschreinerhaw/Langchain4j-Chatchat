package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Runtime-owned full-scan statistics and bounded views. Profiles are not semantic authorizations. */
final class BoundedAnalysisEvidence {
    static final int INPUT_BUDGET = 64_000;
    private static final int CHUNK_ROWS = 1_000;
    private static final int MAX_FIELDS = 128;
    private static final String VERSION = "bounded_analysis_evidence.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    record Prepared(List<Map<String, Object>> views, String fingerprint, Map<String, Dataset> sources,
                    boolean projected) {}

    Prepared prepare(List<Dataset> datasets, AnalysisEvidenceSpillStore store,
                     GovernanceIsolationScope scope, Map<String, Object> metadata, Runnable guard) {
        Map<String, Dataset> sources = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (Dataset dataset : datasets) {
            int count = occurrences.merge(dataset.reference(), 1, Integer::sum);
            String ref = count == 1 ? dataset.reference() : dataset.reference() + "#occurrence-" + count;
            if (sources.putIfAbsent(ref, dataset) != null) throw new IllegalStateException("Dataset reference collision: " + ref);
        }
        List<Map<String, Object>> direct = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        int chars = 0;
        for (var source : sources.entrySet()) {
            guard.run();
            Map<String, Object> view = Map.of("datasetReference", source.getKey(),
                "recordReferenceFormat", source.getKey() + ".records[1] (one-based)",
                "context", source.getValue().analysisContext(), "records", source.getValue().records());
            hashes.add(ModelProtocolJson.sha256Hex(view));
            // Do not construct a giant combined JSON string just to measure the prompt.
            if (chars <= INPUT_BUDGET) {
                chars += ModelProtocolJson.compact(view).length();
                if (chars <= INPUT_BUDGET) direct.add(view);
            }
        }
        String fingerprint = ModelProtocolJson.sha256Hex(hashes);
        if (chars <= INPUT_BUDGET) {
            metadata.put("unifiedEvidenceMode", "FULL_RECORDS");
            return new Prepared(List.copyOf(direct), fingerprint, sources, false);
        }
        int perDataset = INPUT_BUDGET / Math.max(1, sources.size()) - 100;
        if (perDataset < 2_000) throw new IllegalStateException("Dataset catalog exceeds evidence budget");
        List<Map<String, Object>> views = new ArrayList<>();
        List<Map<String, Object>> coverage = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (var source : sources.entrySet()) {
                String ref = source.getKey();
                Dataset dataset = source.getValue();
                List<Map<String, Object>> partials = new ArrayList<>();
                int restored = 0;
                // Four outstanding tasks maximum; failed tasks leave successful checkpoints reusable.
                for (int start = 0; start < dataset.records().size(); start += CHUNK_ROWS * 4) {
                    guard.run();
                    List<Future<Map<String, Object>>> batch = new ArrayList<>();
                    for (int offset = start; offset < Math.min(start + CHUNK_ROWS * 4, dataset.records().size()); offset += CHUNK_ROWS) {
                        int from = offset;
                        int to = Math.min(from + CHUNK_ROWS, dataset.records().size());
                        batch.add(executor.submit(() -> profileChunk(ref, dataset.records().subList(from, to), from, store, scope, guard)));
                    }
                    try {
                        for (Future<Map<String, Object>> task : batch) {
                            Map<String, Object> result = task.get();
                            partials.add(result);
                            if (Boolean.TRUE.equals(result.get("restored"))) restored++;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Evidence profiling interrupted");
                    } catch (ExecutionException failed) {
                        if (failed.getCause() instanceof RuntimeException cause) throw cause;
                        throw new IllegalStateException("Evidence profiling failed", failed.getCause());
                    } finally {
                        batch.forEach(task -> { if (!task.isDone()) task.cancel(true); });
                    }
                }
                Map<String, Object> profile = merge(partials, dataset.records().size());
                LinkedHashSet<Integer> selected = new LinkedHashSet<>();
                if (!dataset.records().isEmpty()) { selected.add(1); selected.add(dataset.records().size()); }
                for (Map<String, Object> field : maps(profile.get("fields"))) {
                    if (selected.size() >= 24) break;
                    if (field.get("minRecord") instanceof Number n) selected.add(n.intValue());
                    if (field.get("maxRecord") instanceof Number n) selected.add(n.intValue());
                }
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("datasetReference", ref);
                view.put("recordCount", dataset.records().size());
                view.put("evidenceMode", "FULL_SCAN_PROFILE_WITH_SELECTED_RECORDS");
                view.put("profile", fit(profile, perDataset / 3));
                view.put("context", fit(dataset.analysisContext(), perDataset / 3));
                view.put("selectedRecords", rows(ref, dataset, selected, List.of(), perDataset / 4));
                view.put("limitations", List.of("Selected records are navigation evidence, not a representative sample.",
                    "Structural numeric statistics do not authorize business aggregation or causal claims.",
                    "Omitted values remain available through bounded READ_RECORDS requests; profiling is not semantic review of every row."));
                views.add(view);
                coverage.add(Map.of("datasetReference", ref, "scannedRecords", dataset.records().size(),
                    "chunkCount", partials.size(), "restoredChunks", restored, "scanComplete", true));
            }
        } finally { executor.shutdownNow(); }
        if (ModelProtocolJson.compact(views).length() > INPUT_BUDGET)
            throw new IllegalStateException("Evidence catalog exceeds bounded projection budget");
        metadata.put("unifiedEvidenceMode", "BOUNDED_PROJECTION");
        metadata.put("unifiedEvidenceScanCoverage", coverage);
        metadata.put("unifiedEvidenceMaxConcurrentPartitions", 4);
        return new Prepared(List.copyOf(views), fingerprint, sources, true);
    }

    private Map<String, Object> profileChunk(String ref, List<Map<String, Object>> rows, int offset,
        AnalysisEvidenceSpillStore store, GovernanceIsolationScope scope, Runnable guard) {
        guard.run();
        String hash = ModelProtocolJson.sha256Hex(rows);
        String key = VERSION + ":" + ref + ":" + offset;
        try {
            String cached = store.readCheckpoint(scope, key, hash).orElse("");
            Map<String, Object> restored = JSON.readValue(cached, new TypeReference<>() {});
            if (restored != null && VERSION.equals(restored.get("version"))
                && hash.equals(restored.get("inputHash")) && restored.get("fields") instanceof List<?>) {
                restored.put("restored", true);
                return restored;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { /* Recompute corrupt checkpoint. */ }
        Map<String, Stats> fields = new LinkedHashMap<>();
        boolean omitted = false;
        for (int i = 0; i < rows.size(); i++) {
            if (i % 100 == 0) { guard.run(); if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
            for (var cell : rows.get(i).entrySet()) {
                Stats stats = fields.get(cell.getKey());
                if (stats == null) {
                    if (fields.size() == MAX_FIELDS) { omitted = true; continue; }
                    stats = new Stats(cell.getKey()); fields.put(cell.getKey(), stats);
                }
                stats.add(cell.getValue(), offset + i + 1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION); result.put("inputHash", hash);
        result.put("rowCount", rows.size()); result.put("fieldsOmitted", omitted);
        result.put("fields", fields.values().stream().map(Stats::map).toList());
        store.checkpoint(scope, key, hash, ModelProtocolJson.compact(result));
        return result;
    }

    private Map<String, Object> merge(List<Map<String, Object>> partials, int rows) {
        Map<String, Stats> fields = new LinkedHashMap<>();
        boolean omitted = false;
        for (var partial : partials) {
            omitted |= Boolean.TRUE.equals(partial.get("fieldsOmitted"));
            for (var field : maps(partial.get("fields"))) {
                String name = (String) field.get("field");
                if (!fields.containsKey(name) && fields.size() == MAX_FIELDS) { omitted = true; continue; }
                fields.computeIfAbsent(name, Stats::new).merge(field);
            }
        }
        // If a field was excluded in any partition its global totals cannot be advertised as exact.
        return Map.of("rowCount", rows, "fields", fields.values().stream().map(Stats::map).toList(),
            "fieldStatisticsComplete", !omitted, "statisticsRole", "STRUCTURAL_NAVIGATION_ONLY");
    }

    List<Map<String, Object>> read(Prepared prepared, Object requested, Runnable guard) {
        return read(prepared, requested, guard, null, null, null, "", new LinkedHashMap<>());
    }

    List<Map<String, Object>> read(Prepared prepared, Object requested, Runnable guard,
        dev.langchain4j.model.chat.ChatModel model, GovernanceIsolationScope scope,
        AnalysisEvidenceSpillStore store, String question, Map<String, Object> metadata) {
        List<Map<String, Object>> requests = maps(requested);
        if (requests.size() > 4) throw new IllegalArgumentException("At most four evidence requests per round");
        List<Map<String, Object>> results = new ArrayList<>();
        for (var request : requests) {
            guard.run();
            String ref = String.valueOf(request.get("datasetReference"));
            Dataset dataset = prepared.sources().get(ref);
            if (dataset == null) throw new IllegalArgumentException("Evidence request cites an unbound dataset");
            if ("CALCULATE".equals(request.get("operation"))) {
                var result = new SupplementaryFormulaExecutor().execute(dataset.analysisContext(), request);
                results.add(Map.of("datasetReference", ref, "calculation", fit(result, 9000)));
                metadata.put("supplementaryFormulaCount", ((Number) metadata.getOrDefault("supplementaryFormulaCount", 0)).intValue() + 1);
                continue;
            }
            if ("EXTRACT_TEXT".equals(request.get("operation"))) {
                int row = integer(request.get("record"));
                String field = String.valueOf(request.get("field"));
                if (row < 1 || row > dataset.records().size() || !(dataset.records().get(row - 1).get(field) instanceof String text))
                    throw new IllegalArgumentException("Text extraction requires an original string field");
                var result = new TextPartitionExtractor().extract(text, ref + ".records[" + row + "]", field,
                    question, integer(request.getOrDefault("fromChar", 0)), model, scope, store, guard,
                    Math.max(0, 64 - ((Number) metadata.getOrDefault("textExtractionModelCalls", 0)).intValue()));
                var stableEvidence = new LinkedHashMap<>(result);
                stableEvidence.remove("modelCalls"); stableEvidence.remove("restoredPartitions");
                results.add(Map.of("datasetReference", ref, "extraction", fit(stableEvidence, 9000)));
                metadata.put("textExtractionModelCalls", ((Number) metadata.getOrDefault("textExtractionModelCalls", 0)).intValue()
                    + ((Number) result.get("modelCalls")).intValue());
                continue;
            }
            if ("READ_CONTEXT".equals(request.get("operation"))) {
                if (!(request.get("path") instanceof List<?> path) || path.isEmpty() || path.size() > 8)
                    throw new IllegalArgumentException("Invalid context path");
                Object value = dataset.analysisContext();
                for (Object part : path) {
                    if (!(part instanceof String) || !(value instanceof Map<?, ?> map) || !map.containsKey(part))
                        throw new IllegalArgumentException("Context path is unavailable");
                    value = map.get(part);
                }
                int totalItems = value instanceof List<?> list ? list.size() : 1;
                if (value instanceof List<?> list) {
                    int from = integer(request.getOrDefault("fromItem", 0));
                    int limit = integer(request.getOrDefault("limit", 5));
                    if (from < 0 || from > list.size() || limit < 1 || limit > 20)
                        throw new IllegalArgumentException("Invalid context page");
                    value = list.subList(from, Math.min(from + limit, list.size()));
                }
                results.add(Map.of("datasetReference", ref, "contextPath", path, "totalItems", totalItems,
                    "value", fit(value, 9_000)));
                continue;
            }
            if (!"READ_RECORDS".equals(request.get("operation")))
                throw new IllegalArgumentException("Unsupported evidence operation");
            int from = integer(request.get("fromRecord"));
            int limit = integer(request.get("limit"));
            if (from < 1 || from > dataset.records().size() || limit < 1 || limit > 100)
                throw new IllegalArgumentException("Invalid bounded evidence range");
            List<String> fields = request.get("fields") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
            List<Integer> indices = java.util.stream.IntStream.range(from, Math.min(dataset.records().size() + 1, from + limit)).boxed().toList();
            results.add(Map.of("datasetReference", ref, "requestedFromRecord", from,
                "requestedLimit", limit, "rows", rows(ref, dataset, indices, fields, 10_000)));
        }
        return results;
    }

    private List<Map<String, Object>> rows(String ref, Dataset dataset, Collection<Integer> indices,
                                          List<String> fields, int budget) {
        List<Map<String, Object>> result = new ArrayList<>();
        int used = 2;
        for (int index : indices) {
            Map<String, Object> record = new LinkedHashMap<>(dataset.records().get(index - 1));
            if (!fields.isEmpty()) record.keySet().retainAll(fields);
            Map<String, Object> row = Map.of("recordRef", ref + ".records[" + index + "]",
                "record", fit(record, Math.min(2_000, budget / 2)));
            int chars = ModelProtocolJson.compact(row).length() + 1;
            if (used + chars > budget) {
                result.add(Map.of("remainingRecordsOmitted", true, "nextRecord", index)); break;
            }
            result.add(row); used += chars;
        }
        return result;
    }

    /** Omission is explicit and never substitutes a shortened scalar for an exact value. */
    private Object fit(Object value, int budget) {
        if (ModelProtocolJson.compact(value).length() <= budget) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int share = Math.max(80, (budget - 150) / Math.max(1, map.size()));
            for (var entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), fit(entry.getValue(), share));
            if (ModelProtocolJson.compact(result).length() <= budget) return result;
        }
        if (value instanceof List<?> list) {
            List<Object> selected = new ArrayList<>();
            int used = 100;
            for (Object item : list) {
                Object bounded = fit(item, Math.max(100, budget / 4));
                int size = ModelProtocolJson.compact(bounded).length() + 1;
                if (used + size > budget) break;
                selected.add(bounded); used += size;
            }
            return Map.of("selectedItems", selected, "totalItems", list.size(), "omitted", true);
        }
        return Map.of("omitted", true, "reason", "VALUE_EXCEEDS_VIEW_BUDGET");
    }

    private int integer(Object value) {
        try { return new BigDecimal(String.valueOf(value)).intValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Expected integer evidence range"); }
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList() : List.of();
    }
    private static class Stats {
        final String field; long present, numeric; BigDecimal sum = BigDecimal.ZERO, min, max;
        int minRecord, maxRecord;
        Stats(String field) { this.field = field; }
        void add(Object value, int row) {
            if (value == null) return;
            present++;
            if (!(value instanceof Number)) return;
            try {
                BigDecimal number = new BigDecimal(value.toString()); numeric++; sum = sum.add(number);
                if (min == null || number.compareTo(min) < 0) { min = number; minRecord = row; }
                if (max == null || number.compareTo(max) > 0) { max = number; maxRecord = row; }
            } catch (NumberFormatException nonFinite) { /* Non-finite numbers are not aggregatable. */ }
        }
        void merge(Map<String, Object> part) {
            present += ((Number) part.get("presentCount")).longValue();
            numeric += ((Number) part.get("numericCount")).longValue();
            sum = sum.add(new BigDecimal(part.get("sum").toString()));
            if (part.containsKey("min")) {
                BigDecimal low = new BigDecimal(part.get("min").toString());
                BigDecimal high = new BigDecimal(part.get("max").toString());
                if (min == null || low.compareTo(min) < 0) { min = low; minRecord = ((Number) part.get("minRecord")).intValue(); }
                if (max == null || high.compareTo(max) > 0) { max = high; maxRecord = ((Number) part.get("maxRecord")).intValue(); }
            }
        }
        Map<String, Object> map() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("field", field); value.put("presentCount", present); value.put("numericCount", numeric); value.put("sum", sum);
            if (min != null) { value.put("min", min); value.put("max", max); value.put("minRecord", minRecord); value.put("maxRecord", maxRecord); }
            return value;
        }
    }
}
