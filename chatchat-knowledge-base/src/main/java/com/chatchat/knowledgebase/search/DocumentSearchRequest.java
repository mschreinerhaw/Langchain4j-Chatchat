package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchRequest(
    String query,
    Integer topK,
    List<String> fileIds,
    DocumentSearchFilters filters,
    String tenantId,
    String userId,
    List<String> roles,
    Boolean debug
) {
}
