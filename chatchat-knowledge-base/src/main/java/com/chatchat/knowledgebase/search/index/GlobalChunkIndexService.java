package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.service.SearchService;

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
                indexTag(filters),
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

    private String indexTag(DocumentSearchFilters filters) {
        return filters == null || filters.allTags().size() != 1 ? null : filters.allTags().get(0);
    }

    private String safeLogQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
