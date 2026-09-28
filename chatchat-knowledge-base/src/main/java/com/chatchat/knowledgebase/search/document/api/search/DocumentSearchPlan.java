package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.retrieval.RetrievalValidationResult;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import java.util.List;

public record DocumentSearchPlan(
    String query,
    int topK,
    DocumentSearchFilters filters,
    List<String> scopedFileIds,
    List<String> effectiveScopedFileIds,
    List<String> visibilityScopeIds,
    String joinedVisibilityScopeIds,
    String intent,
    List<String> queryTokens,
    boolean debug,
    SearchPermissionContext permissionContext,
    DocumentVisibilityContext visibilityContext,
    RetrievalValidationResult validation
) {
}
