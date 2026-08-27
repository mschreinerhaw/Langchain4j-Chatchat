package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.service.SearchService;

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
