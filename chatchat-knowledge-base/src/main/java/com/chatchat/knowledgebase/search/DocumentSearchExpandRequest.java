package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchExpandRequest(
    String query,
    String docId,
    List<String> sections,
    Integer topK,
    Integer maxSections,
    Integer maxChunks,
    Integer maxTotalChars,
    String tenantId,
    String userId,
    List<String> roles,
    Boolean debug
) {
}
