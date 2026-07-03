package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalChunkIndexService {

    private final SearchService searchService;
    private final SearchProperties properties;

    public SearchPage recall(DocumentSearchPlan plan) {
        SearchProperties.HybridRetrieval hybrid = properties.getHybridRetrieval();
        if (hybrid == null || !hybrid.isEnabled()) {
            return null;
        }
        DocumentSearchFilters filters = plan.filters();
        try {
            return searchService.search(
                plan.query(),
                filters == null ? null : filters.tag(),
                filters == null ? null : filters.company(),
                filters == null ? null : filters.industry(),
                plan.joinedVisibilityScopeIds(),
                1,
                Math.max(1, hybrid.getGlobalChunkLimit()),
                plan.permissionContext()
            );
        } catch (Exception ex) {
            log.warn("document_search_global_chunk_recall_failed query='{}' error={}", safeLogQuery(plan.query()), ex.getMessage(), ex);
            return null;
        }
    }

    private String safeLogQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
