package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Runtime-owned full-scan statistics and bounded views. Profiles are not semantic authorizations. */
final class BoundedAnalysisEvidence {
    static final int INPUT_BUDGET = 30_000;
    static final int DIRECT_RECORD_BUDGET = 12_000;
    static final int REQUEST_RESULT_BUDGET = 4_000;
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
        boolean directEligible = true;
        for (var source : sources.entrySet()) {
            guard.run();
            // A direct view is attempted only for handles that can be read cheaply. Large and
            // cursor-backed handles go straight to the bounded full-scan path.
            if (source.getValue().recordCount() > CHUNK_ROWS) {
                directEligible = false;
                break;
            }
            List<Map<String, Object>> records = source.getValue().handle().readPage(
                0, Math.max(1, Math.toIntExact(source.getValue().recordCount()))).rows();
            Map<String, Object> view = Map.of("datasetReference", source.getKey(),
                "recordReferenceFormat", source.getKey() + ".records[1] (one-based)",
                "recordCount", source.getValue().recordCount(),
                "nestedCollections", NestedRecordReader.catalog(records),
                "context", source.getValue().analysisContext(), "records", records);
            hashes.add(ModelProtocolJson.sha256Hex(view));
            // Do not construct a giant combined JSON string just to measure the prompt.
            if (chars <= DIRECT_RECORD_BUDGET) {
                chars += ModelProtocolJson.compact(view).length();
                if (chars <= DIRECT_RECORD_BUDGET) direct.add(view);
            }
        }
        String fingerprint = ModelProtocolJson.sha256Hex(hashes);
        if (directEligible && direct.size() == sources.size() && chars <= DIRECT_RECORD_BUDGET) {
            metadata.put("unifiedEvidenceMode", "FULL_RECORDS");
            return new Prepared(List.copyOf(direct), fingerprint, sources, false);
        }
        int perDataset = INPUT_BUDGET / Math.max(1, sources.size()) - 100;
        if (perDataset < 2_000) throw new IllegalStateException("Dataset catalog exceeds evidence budget");
        List<Map<String, Object>> views = new ArrayList<>();
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (var source : sources.entrySet()) {
                String ref = source.getKey();
                Dataset dataset = source.getValue();
                ProfileAccumulator accumulated = new ProfileAccumulator();
                int[] restored = {0};
                int[] chunks = {0};
                java.security.MessageDigest chunkDigest = sha256Digest();
                dataset.handle().scan(CHUNK_ROWS, page -> {
                    guard.run();
                    Map<String, Object> partial = profileChunk(
                        ref, page.rows(), Math.toIntExact(page.offset()), store, scope, guard);
                    accumulated.add(partial);
                    chunks[0]++;
                    if (Boolean.TRUE.equals(partial.get("restored"))) restored[0]++;
                    chunkDigest.update(String.valueOf(partial.get("inputHash"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    chunkDigest.update((byte) '\n');
                });
                hashes.add(java.util.HexFormat.of().formatHex(chunkDigest.digest()));
                Map<String, Object> profile = accumulated.finish(dataset.recordCount());
                LinkedHashSet<Integer> selected = new LinkedHashSet<>();
                if (dataset.recordCount() > 0) { selected.add(1); selected.add(Math.toIntExact(dataset.recordCount())); }
                for (Map<String, Object> field : maps(profile.get("fields"))) {
                    if (selected.size() >= 24) break;
                    if (field.get("minRecord") instanceof Number n) selected.add(n.intValue());
                    if (field.get("maxRecord") instanceof Number n) selected.add(n.intValue());
                }
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("datasetReference", ref);
                view.put("recordCount", dataset.recordCount());
                int catalogRows = (int) Math.min(100, dataset.recordCount());
                view.put("nestedCollections", fit(NestedRecordReader.catalog(
                    catalogRows == 0 ? List.of() : dataset.handle().readPage(0, catalogRows).rows()), perDataset / 5));
                view.put("evidenceMode", "FULL_SCAN_PROFILE_WITH_SELECTED_RECORDS");
                view.put("profile", fit(profile, perDataset / 3));
                view.put("context", fit(dataset.analysisContext(), perDataset / 3));
                view.put("selectedRecords", rows(ref, dataset, selected, List.of(), perDataset / 4));
                view.put("limitations", List.of("Selected records are navigation evidence, not a representative sample.",
                    "Structural numeric statistics do not authorize business aggregation or causal claims.",
                    "Omitted values remain available through bounded READ_RECORDS requests; profiling is not semantic review of every row."));
                views.add(view);
                coverage.add(Map.of("datasetReference", ref, "scannedRecords", Math.toIntExact(dataset.recordCount()),
                    "chunkCount", chunks[0], "restoredChunks", restored[0], "scanComplete", true));
        }
        fingerprint = ModelProtocolJson.sha256Hex(hashes);
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

    private java.security.MessageDigest sha256Digest() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private final class ProfileAccumulator {
        private final Map<String, Stats> fields = new LinkedHashMap<>();
        private boolean omitted;

        void add(Map<String, Object> partial) {
            omitted |= Boolean.TRUE.equals(partial.get("fieldsOmitted"));
            for (var field : maps(partial.get("fields"))) {
                String name = (String) field.get("field");
                if (!fields.containsKey(name) && fields.size() == MAX_FIELDS) {
                    omitted = true;
                    continue;
                }
                fields.computeIfAbsent(name, Stats::new).merge(field);
            }
        }

        Map<String, Object> finish(long rows) {
            return Map.of("rowCount", rows,
                "fields", fields.values().stream().map(Stats::map).toList(),
                "fieldStatisticsComplete", !omitted,
                "statisticsRole", "STRUCTURAL_NAVIGATION_ONLY");
        }
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
            if ("READ_NESTED_RECORDS".equals(request.get("operation"))) {
                int record = integer(request.get("record"));
                if (record < 1 || record > dataset.recordCount())
                    throw new IllegalArgumentException("Invalid nested record locator");
                Map<String, Object> parent = dataset.handle().readPage(record - 1L, 1).rows().get(0);
                results.add(Map.of("datasetReference", ref,
                    "parentRecordRef", ref + ".records[" + request.get("record") + "]",
                    "nestedPage", fit(NestedRecordReader.readRecord(parent, record, request), REQUEST_RESULT_BUDGET)));
                continue;
            }
            if ("CALCULATE".equals(request.get("operation"))) {
                var result = new SupplementaryFormulaExecutor().execute(dataset.analysisContext(), request);
                results.add(Map.of("datasetReference", ref, "calculation", fit(result, REQUEST_RESULT_BUDGET)));
                metadata.put("supplementaryFormulaCount", ((Number) metadata.getOrDefault("supplementaryFormulaCount", 0)).intValue() + 1);
                continue;
            }
            if ("EXECUTE_OPERATION".equals(request.get("operation"))) {
                String analysisOperation = String.valueOf(request.get("analysisOperation"));
                if (!(request.get("specification") instanceof Map<?, ?> rawSpecification))
                    throw new IllegalArgumentException("Pushdown operation requires a specification");
                Map<String, Object> specification = new LinkedHashMap<>();
                rawSpecification.forEach((key, value) -> specification.put(String.valueOf(key), value));
                var executed = dataset.handle().execute(
                    new com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle.OperationRequest(
                        analysisOperation, specification));
                if (executed.isEmpty())
                    throw new IllegalArgumentException("Dataset handle does not support calculation pushdown");
                results.add(Map.of("datasetReference", ref, "executedOperation",
                    fit(Map.of("analysisOperation", analysisOperation,
                        "value", executed.get().value(), "lineage", executed.get().lineage()),
                        REQUEST_RESULT_BUDGET)));
                continue;
            }
            if ("EXTRACT_TEXT".equals(request.get("operation"))) {
                int row = integer(request.get("record"));
                String field = String.valueOf(request.get("field"));
                Map<String, Object> original = row < 1 || row > dataset.recordCount() ? Map.of()
                    : dataset.handle().readPage(row - 1L, 1).rows().get(0);
                if (!(original.get(field) instanceof String text))
                    throw new IllegalArgumentException("Text extraction requires an original string field");
                var result = new TextPartitionExtractor().extract(text, ref + ".records[" + row + "]", field,
                    question, integer(request.getOrDefault("fromChar", 0)), model, scope, store, guard,
                    Math.max(0, 64 - ((Number) metadata.getOrDefault("textExtractionModelCalls", 0)).intValue()));
                var stableEvidence = new LinkedHashMap<>(result);
                stableEvidence.remove("modelCalls"); stableEvidence.remove("restoredPartitions");
                results.add(Map.of("datasetReference", ref, "extraction", fit(stableEvidence, REQUEST_RESULT_BUDGET)));
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
                    "value", fit(value, REQUEST_RESULT_BUDGET)));
                continue;
            }
            if (!"READ_RECORDS".equals(request.get("operation")))
                throw new IllegalArgumentException("Unsupported evidence operation");
            int from = integer(request.get("fromRecord"));
            int limit = integer(request.get("limit"));
            if (from < 1 || from > dataset.recordCount() || limit < 1 || limit > 100)
                throw new IllegalArgumentException("Invalid bounded evidence range");
            List<String> fields = request.get("fields") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
            List<Integer> indices = java.util.stream.IntStream.range(from,
                Math.toIntExact(Math.min(dataset.recordCount() + 1, (long) from + limit))).boxed().toList();
            results.add(Map.of("datasetReference", ref, "requestedFromRecord", from,
                "requestedLimit", limit, "rows", rows(ref, dataset, indices, fields, REQUEST_RESULT_BUDGET)));
        }
        return results;
    }

    Object fitViews(List<Map<String, Object>> views, int budget) {
        if (views == null || views.isEmpty()) return List.of();
        int share = Math.max(500, budget / views.size());
        return views.stream().map(view -> fit(view, share)).toList();
    }

    Object fitRequestedEvidence(List<Map<String, Object>> evidence, int budget) {
        return fit(evidence == null ? List.of() : evidence, budget);
    }

    private List<Map<String, Object>> rows(String ref, Dataset dataset, Collection<Integer> indices,
                                          List<String> fields, int budget) {
        List<Map<String, Object>> result = new ArrayList<>();
        int used = 2;
        for (int index : indices) {
            List<Map<String, Object>> page = dataset.handle().readPage(index - 1L, 1).rows();
            if (page.isEmpty()) break;
            Map<String, Object> record = new LinkedHashMap<>(page.get(0));
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
