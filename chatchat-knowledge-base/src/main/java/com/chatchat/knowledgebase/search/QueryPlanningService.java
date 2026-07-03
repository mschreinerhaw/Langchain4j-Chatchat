package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 30;

    private final SearchTokenizer tokenizer;
    private final QueryIntentClassifier intentClassifier;
    private final RetrievalQueryValidator queryValidator;
    private final SearchPermissionGuard permissionGuard;

    public DocumentSearchPlan plan(DocumentSearchRequest request) {
        String query = requireQuery(request == null ? null : request.query());
        int topK = normalizeTopK(request == null ? null : request.topK());
        DocumentSearchFilters filters = request == null ? null : request.filters();
        List<String> scopedFileIds = request == null ? List.of() : cleanList(request.fileIds());
        SearchPermissionContext permissionContext = permissionGuard.permissionContext(request);
        DocumentVisibilityContext visibilityContext = permissionGuard.visibilityContext(request, permissionContext);
        List<String> effectiveScopedFileIds = visibilityContext.active()
            ? visibilityContext.filterIds(scopedFileIds)
            : scopedFileIds;
        List<String> visibilityScopeIds = visibilityContext.active() && scopedFileIds.isEmpty()
            ? List.copyOf(visibilityContext.allowedFileIds())
            : effectiveScopedFileIds;
        String intent = intentClassifier.classifyName(query);
        List<String> queryTokens = tokenizer.searchTokens(query);
        RetrievalValidationResult validation = queryValidator.validate(query, !visibilityScopeIds.isEmpty(), filters);
        return new DocumentSearchPlan(
            query,
            topK,
            filters,
            scopedFileIds,
            effectiveScopedFileIds,
            visibilityScopeIds,
            joinValues(visibilityScopeIds),
            intent,
            queryTokens,
            request != null && Boolean.TRUE.equals(request.debug()),
            permissionContext,
            visibilityContext,
            validation
        );
    }

    private List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String joinValues(List<String> values) {
        return cleanList(values).stream().collect(Collectors.joining(","));
    }

    private int normalizeTopK(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(value, MAX_TOP_K);
    }

    private String requireQuery(String query) {
        if (!hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        return query.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
