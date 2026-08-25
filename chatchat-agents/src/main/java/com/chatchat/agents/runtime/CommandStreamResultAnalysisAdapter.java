package com.chatchat.agents.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Protocol adapter for command-execution results that publish stdout/stderr streams. */
final class CommandStreamResultAnalysisAdapter implements McpResultAnalysisAdapter {

    @Override
    public String id() {
        return "command_stream.v1";
    }

    @Override
    public int priority() {
        return 1_000;
    }

    @Override
    public boolean supports(AnalysisRequest request) {
        Map<String, Object> root = map(request.payload());
        String dataSchema = string(root.get("dataSchema"));
        String kind = string(root.get("kind"));
        String operationType = string(map(root.get("operation")).get("type"));
        return "ssh_steps.v1".equals(dataSchema)
            || startsWithIgnoreCase(dataSchema, "ssh_")
            || "ssh_command".equalsIgnoreCase(kind)
            || startsWithIgnoreCase(operationType, "ssh.");
    }

    @Override
    public AnalysisResult adapt(AnalysisRequest request) {
        Map<String, Object> root = map(request.payload());
        Map<String, Object> data = map(root.get("data"));
        Map<String, Object> limits = map(data.get("outputLimits"));
        List<AnalysisDataset> datasets = new ArrayList<>();
        addStream(datasets, request, "stdout", data.get("stdout"),
            !booleanValue(limits.get("stdoutTruncated")));
        addStream(datasets, request, "stderr", data.get("stderr"),
            !booleanValue(limits.get("stderrTruncated")));
        return new AnalysisResult(string(root.get("dataSchema")),
            "COMMAND_EXECUTION_STREAMS", datasets);
    }

    private void addStream(List<AnalysisDataset> datasets,
                           AnalysisRequest request,
                           String stream,
                           Object value,
                           boolean sourceComplete) {
        String content = value == null ? null : String.valueOf(value);
        if (content == null || content.isBlank()) return;
        List<Map<String, Object>> records = new ArrayList<>();
        int chunkChars = Math.max(1_000, request.maximumRecordChars());
        int chunkIndex = 0;
        for (int from = 0; from < content.length(); from += chunkChars) {
            int to = Math.min(content.length(), from + chunkChars);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("stream", stream);
            record.put("chunkIndex", ++chunkIndex);
            record.put("fromChar", from);
            record.put("toChar", to);
            record.put("sourceComplete", sourceComplete);
            record.put("content", content.substring(from, to));
            records.add(Map.copyOf(record));
        }
        datasets.add(new AnalysisDataset(
            text(request.datasetReference(), "result") + "#" + stream,
            Map.of("contentType", "text/plain", "stream", stream), records));
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String string(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }
}
