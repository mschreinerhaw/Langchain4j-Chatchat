package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Deterministically partitions complete returned records without sampling. */
public final class AnalysisRecordChunkPlanner {

    private final ObjectMapper objectMapper;

    public AnalysisRecordChunkPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public ChunkPlan plan(List<Map<String, Object>> records,
                          int maximumChunkRows,
                          int maximumChunkChars) {
        List<Map<String, Object>> safeRecords = records == null ? List.of() : records;
        int rowLimit = Math.max(1, maximumChunkRows);
        int charLimit = Math.max(1, maximumChunkChars);
        List<Range> ranges = new ArrayList<>();
        int currentFrom = 0;
        int currentRows = 0;
        int currentChars = 0;
        long totalChars = 0;
        for (int index = 0; index < safeRecords.size(); index++) {
            int recordChars = Math.max(1, serializedLength(safeRecords.get(index)));
            totalChars += recordChars;
            if (currentRows > 0 && (currentRows >= rowLimit || currentChars + recordChars > charLimit)) {
                ranges.add(new Range(currentFrom, index));
                currentFrom = index;
                currentRows = 0;
                currentChars = 0;
            }
            currentRows++;
            currentChars += recordChars;
        }
        if (currentRows > 0) ranges.add(new Range(currentFrom, safeRecords.size()));
        return new ChunkPlan(List.copyOf(ranges),
            totalChars > charLimit || safeRecords.size() > rowLimit, totalChars);
    }

    public List<String> valueGroup(Map<String, Object> record, String query) {
        if (record == null || record.isEmpty()) return List.of();
        String normalizedQuery = firstNonBlank(query, "").replace(",", "");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object rawValue : record.values()) {
            if (rawValue instanceof Map<?, ?> || rawValue instanceof Iterable<?> || rawValue == null) continue;
            String value = String.valueOf(rawValue).trim();
            if (value.length() >= 3 && !normalizedQuery.contains(value.replace(",", ""))) values.add(value);
        }
        if (values.isEmpty()) values.add(ModelProtocolJson.compact(record));
        return List.copyOf(values);
    }

    private int serializedLength(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception ignored) {
            return String.valueOf(value).length();
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record Range(int fromInclusive, int toExclusive) { }
    public record ChunkPlan(List<Range> ranges, boolean oversized, long totalChars) { }
}
