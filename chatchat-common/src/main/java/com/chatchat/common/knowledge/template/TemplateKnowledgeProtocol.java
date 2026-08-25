package com.chatchat.common.knowledge.template;

import com.chatchat.common.knowledge.SearchHit;
import com.chatchat.common.knowledge.SearchStatus;
import com.chatchat.common.knowledge.StandardSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Alias-tolerant adapter at the boundary; consumers receive only the canonical records above. */
public final class TemplateKnowledgeProtocol {
    private TemplateKnowledgeProtocol() { }

    public static StandardSearchResult<StandardTemplateKnowledge> searchResult(
        String query,
        List<Map<String, Object>> candidates,
        long totalHits,
        int limit,
        boolean truncated,
        Map<String, Object> metadata
    ) {
        List<SearchHit<StandardTemplateKnowledge>> hits = new ArrayList<>();
        if (candidates != null) {
            int rank = 1;
            for (Map<String, Object> candidate : candidates) {
                StandardTemplateKnowledge document = template(candidate);
                if (document == null) continue;
                hits.add(new SearchHit<>(rank++, score(candidate), document, evidence(candidate)));
            }
        }
        int candidateCount = candidates == null ? 0 : candidates.size();
        Map<String, Object> resultMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        resultMetadata.put("candidateCount", candidateCount);
        resultMetadata.put("acceptedHitCount", hits.size());
        resultMetadata.put("rejectedCandidateCount", Math.max(0, candidateCount - hits.size()));
        SearchStatus status = hits.isEmpty() ? SearchStatus.EMPTY
            : hits.size() < candidateCount ? SearchStatus.PARTIAL : SearchStatus.FOUND;
        return new StandardSearchResult<>(StandardSearchResult.SCHEMA_VERSION, query,
            status, hits, Math.max(totalHits, hits.size()), limit, truncated,
            resultMetadata, System.currentTimeMillis());
    }

    public static StandardTemplateKnowledge template(Map<String, Object> candidate) {
        if (candidate == null) return null;
        String templateId = text(first(candidate, "templateId", "template_id", "id", "code", "template"));
        if (templateId == null) return null;
        Map<String, Object> parameterSchema = map(first(candidate,
            "parameterSchema", "parameter_schema", "inputSchema", "input_schema", "schema"));
        Map<String, Object> outputSchema = map(first(candidate,
            "outputSchema", "output_schema", "resultSchema", "result_schema"));
        List<String> required = strings(first(candidate, "requiredParameters", "required_parameters"));
        if (required.isEmpty()) required = strings(parameterSchema.get("required"));
        String executor = firstText(
            text(first(candidate, "executionTool", "execution_tool", "executorTool", "executor_tool", "toolName")),
            text(nested(candidate, "parameterContract", "executionTool")),
            text(nested(candidate, "invocationExample", "tool")),
            text(nested(candidate, "executionBinding", "toolName")),
            text(nested(candidate, "sqlExecutionBinding", "toolName")));
        return new StandardTemplateKnowledge(templateId,
            firstText(text(first(candidate, "templateType", "template_type", "assetType", "kind")), "generic"),
            firstText(executor, ""), firstText(text(candidate.get("title")), templateId),
            firstText(text(first(candidate, "summary", "description")), ""), parameterSchema,
            outputSchema, required, new LinkedHashMap<>(candidate));
    }

    private static Map<String, Object> evidence(Map<String, Object> candidate) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        copy(candidate, evidence, "relevanceScore", "score", "confidence", "retrievalSource",
            "matchedTerms", "retrievalEvidence");
        return Map.copyOf(evidence);
    }

    private static double score(Map<String, Object> candidate) {
        Object value = first(candidate, "relevanceScore", "score", "confidence");
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0D : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0D; }
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
        }
    }

    private static Object nested(Map<String, Object> source, String parent, String key) {
        return map(source.get(parent)).get(key);
    }

    private static Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) if (source.containsKey(key) && source.get(key) != null) return source.get(key);
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static String text(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = text(item);
            if (text != null && !result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }
}
