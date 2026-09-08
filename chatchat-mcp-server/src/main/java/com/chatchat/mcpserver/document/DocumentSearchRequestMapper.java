package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps the stable MCP document-search contract to the knowledge-base input model. */
@Component
public class DocumentSearchRequestMapper {

    public DocumentSearchRequest map(Map<String, Object> arguments, Integer fallbackTopK) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        Map<String, Object> filters = mapValue(input.get("filters"));
        return new DocumentSearchRequest(
            text(first(input, "query", "q", "intentText")),
            integer(first(input, "topK", "limit"), fallbackTopK),
            strings(first(input, "fileIds", "fileIdsText", "documentIds", "documentIdsText", "docIds", "docIdsText")),
            strings(first(input, "selectedFileIds", "selected_file_ids")),
            strings(first(input, "selectedDocumentIds", "selected_document_ids", "allowedDocIds", "allowed_document_ids")),
            bool(first(input, "documentVisibilityEnforced", "document_visibility_enforced", "strictDocumentScope")),
            filters.isEmpty() ? null : new DocumentSearchFilters(
                text(filters.get("fileType")),
                text(filters.get("chunkType")),
                text(filters.get("tag")),
                text(filters.get("company")),
                text(filters.get("industry"))
            ),
            text(input.get("tenantId")),
            text(input.get("userId")),
            strings(input.get("roles")),
            bool(input.get("debug"))
        );
    }

    private Object first(Map<String, Object> values, String... names) {
        for (String name : names) {
            Object value = values.get(name);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private List<String> strings(Object value) {
        if (value == null) return List.of();
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> add(result, item));
        } else {
            for (String item : String.valueOf(value).split("[,\\r\\n]+")) add(result, item);
        }
        return List.copyOf(result);
    }

    private void add(List<String> values, Object value) {
        String text = text(value);
        if (text != null) values.add(text);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer integer(Object value, Integer fallback) {
        if (value == null) return fallback;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Boolean bool(Object value) {
        if (value == null) return null;
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.valueOf(String.valueOf(value));
    }
}
