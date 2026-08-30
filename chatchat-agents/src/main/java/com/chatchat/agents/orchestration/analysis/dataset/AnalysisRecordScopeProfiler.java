package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Describes returned-record structure without assigning business meaning to that structure. */
public final class AnalysisRecordScopeProfiler {

    public static final String SCHEMA_VERSION = "analysis_record_scope_profile.v1";
    private static final int MAX_EXAMPLES = 3;

    public Map<String, Object> profile(List<Map<String, Object>> records) {
        List<Map<String, Object>> safeRecords = records == null ? List.of() : records;
        LinkedHashSet<String> fieldNames = new LinkedHashSet<>();
        safeRecords.stream().filter(java.util.Objects::nonNull)
            .forEach(record -> fieldNames.addAll(record.keySet()));
        List<Map<String, Object>> fields = new ArrayList<>();
        List<String> constantFields = new ArrayList<>();
        for (String field : fieldNames) {
            int presentCount = 0;
            Map<String, Object> distinct = new LinkedHashMap<>();
            for (Map<String, Object> record : safeRecords) {
                if (record == null || !record.containsKey(field) || record.get(field) == null) continue;
                presentCount++;
                Object value = record.get(field);
                distinct.putIfAbsent(ModelProtocolJson.compact(value), value);
            }
            boolean constant = !safeRecords.isEmpty()
                && presentCount == safeRecords.size() && distinct.size() == 1;
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("field", field);
            profile.put("presentCount", presentCount);
            profile.put("distinctCount", distinct.size());
            profile.put("constantAcrossReturnedRows", constant);
            profile.put("examples", distinct.values().stream().limit(MAX_EXAMPLES)
                .map(this::boundedExample).toList());
            fields.add(Collections.unmodifiableMap(profile));
            if (constant) constantFields.add(field);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("returnedRecordCount", safeRecords.size());
        result.put("fieldProfiles", List.copyOf(fields));
        result.put("constantAcrossReturnedRows", List.copyOf(constantFields));
        result.put("profileRole", "STRUCTURAL_STATISTICS_ONLY_NO_SEMANTIC_INFERENCE");
        return Collections.unmodifiableMap(result);
    }

    private Object boundedExample(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value;
        String text = value instanceof CharSequence sequence
            ? sequence.toString() : ModelProtocolJson.compact(value);
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
