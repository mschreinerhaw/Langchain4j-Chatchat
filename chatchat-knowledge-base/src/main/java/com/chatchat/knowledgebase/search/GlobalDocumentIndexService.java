package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GlobalDocumentIndexService {

    private final SearchService searchService;

    public SearchPage recall(DocumentSearchPlan plan, int limit) {
        DocumentSearchFilters filters = plan.filters();
        return searchService.frontendQuickSearch(
            plan.query(),
            filters == null ? null : filters.tag(),
            filters == null ? null : filters.company(),
            filters == null ? null : filters.industry(),
            plan.joinedVisibilityScopeIds(),
            1,
            Math.max(1, limit),
            plan.permissionContext()
        );
    }
}
