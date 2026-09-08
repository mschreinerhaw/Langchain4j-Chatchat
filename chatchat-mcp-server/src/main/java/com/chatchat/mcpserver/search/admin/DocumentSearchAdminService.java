package com.chatchat.mcpserver.search.admin;

import com.chatchat.knowledgebase.search.document.DocumentSearchEvidenceService;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.mcpserver.document.DocumentSearchRequestMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts local document evidence to the MCP administration search panel. */
@Service
@RequiredArgsConstructor
public class DocumentSearchAdminService {

    private final DocumentSearchEvidenceService evidenceService;
    private final DocumentSearchRequestMapper requestMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> search(Map<String, Object> input, int limit) {
        Map<String, Object> request = input == null ? Map.of() : new LinkedHashMap<>(input);
        long startedAt = System.currentTimeMillis();
        try {
            DocumentSearchResult result = evidenceService.search(requestMapper.map(request, limit));
            Map<String, Object> raw = objectMapper.convertValue(result, new TypeReference<>() { });
            return response(request, raw, Math.max(0L, System.currentTimeMillis() - startedAt), null);
        } catch (RuntimeException exception) {
            return response(request, Map.of(), Math.max(0L, System.currentTimeMillis() - startedAt), exception.getMessage());
        }
    }

    private Map<String, Object> response(Map<String, Object> request,
                                         Map<String, Object> raw,
                                         long durationMs,
                                         String error) {
        List<Map<String, Object>> evidence = rows(raw.get("results"), this::evidenceRow);
        List<Map<String, Object>> candidates = rows(raw.get("documents"), this::candidateRow);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("indexType", "document_search");
        response.put("luceneEnabled", true);
        response.put("request", request);
        response.put("execution", Map.of("mode", "local", "durationMs", durationMs));
        response.put("query", raw.getOrDefault("query", request.get("query")));
        response.put("intent", raw.get("intent"));
        response.put("matchType", raw.get("matchType"));
        response.put("retrievalSemantics", raw.get("retrievalSemantics"));
        response.put("count", evidence.isEmpty() ? candidates.size() : evidence.size());
        response.put("evidenceCount", evidence.size());
        response.put("candidateDocCount", candidates.size());
        response.put("results", evidence.isEmpty() ? candidates : evidence);
        response.put("candidateDocs", candidates);
        response.put("raw", raw);
        if (error != null && !error.isBlank()) response.put("error", error);
        return response;
    }

    private List<Map<String, Object>> rows(Object value,
                                           java.util.function.Function<Map<String, Object>, Map<String, Object>> mapper) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> source) {
                Map<String, Object> row = new LinkedHashMap<>();
                source.forEach((key, field) -> {
                    if (key != null) row.put(String.valueOf(key), field);
                });
                result.add(mapper.apply(row));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> evidenceRow(Map<String, Object> chunk) {
        Map<String, Object> row = new LinkedHashMap<>(chunk);
        row.put("id", first(chunk.get("chunkId"), chunk.get("refId")));
        row.put("kind", "document_evidence");
        row.put("assetType", "document");
        row.put("documentId", chunk.get("fileId"));
        row.put("name", first(chunk.get("fileName"), chunk.get("fileId")));
        row.put("title", first(chunk.get("section"), chunk.get("chunkType")));
        row.put("description", preview(chunk.get("content")));
        return row;
    }

    private Map<String, Object> candidateRow(Map<String, Object> document) {
        Map<String, Object> row = new LinkedHashMap<>(document);
        row.put("id", first(document.get("docId"), document.get("fileId")));
        row.put("kind", "document_candidate");
        row.put("assetType", "document");
        row.put("documentId", first(document.get("docId"), document.get("fileId")));
        row.put("name", first(document.get("fileName"), document.get("title"), document.get("docId")));
        row.put("description", document.get("documentType"));
        return row;
    }

    private String first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private String preview(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
