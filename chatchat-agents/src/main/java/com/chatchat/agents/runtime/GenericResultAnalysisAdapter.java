package com.chatchat.agents.runtime;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless fallback that makes every otherwise unknown bounded result available to analysis. */
final class GenericResultAnalysisAdapter implements RuntimeResultAnalysisAdapter {

    @Override
    public String id() {
        return "generic_bounded_result.v1";
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean fallback() {
        return true;
    }

    @Override
    public boolean supports(AnalysisRequest request) {
        return request.payload() != null;
    }

    @Override
    public AnalysisResult adapt(AnalysisRequest request) {
        String content = ModelProtocolJson.compact(request.payload());
        if (content.isBlank()) {
            return new AnalysisResult(sourceSchema(request.payload()),
                "GENERIC_RUNTIME_RESULT", List.of());
        }
        int chunkChars = Math.max(1_000, request.maximumRecordChars());
        List<Map<String, Object>> records = new ArrayList<>();
        int chunkIndex = 0;
        for (int from = 0; from < content.length(); from += chunkChars) {
            int to = Math.min(content.length(), from + chunkChars);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("chunkIndex", ++chunkIndex);
            record.put("fromChar", from);
            record.put("toChar", to);
            record.put("sourceComplete", true);
            record.put("content", content.substring(from, to));
            records.add(Map.copyOf(record));
        }
        AnalysisDataset dataset = new AnalysisDataset(
            text(request.datasetReference(), "result") + "#payload",
            Map.of(
                "contentType", contentType(request.payload()),
                "projectionMode", "LOSSLESS_FALLBACK",
                "analysisPolicy", Map.of("mode", "PRESERVE_ONLY")),
            records);
        return new AnalysisResult(sourceSchema(request.payload()),
            "GENERIC_RUNTIME_RESULT", List.of(dataset));
    }

    private String sourceSchema(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return null;
        Object schema = map.get("dataSchema");
        if (schema == null) schema = map.get("schemaVersion");
        return schema == null || String.valueOf(schema).isBlank() ? null : String.valueOf(schema);
    }

    private String contentType(Object payload) {
        return payload instanceof CharSequence ? "text/plain" : "application/json";
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
