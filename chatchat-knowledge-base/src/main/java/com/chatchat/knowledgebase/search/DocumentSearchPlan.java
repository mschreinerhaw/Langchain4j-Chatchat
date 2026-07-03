package com.chatchat.knowledgebase.search;

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
