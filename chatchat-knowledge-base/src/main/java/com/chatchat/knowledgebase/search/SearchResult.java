package com.chatchat.knowledgebase.search;

import java.util.List;

public record SearchResult(
    String docId,
    String title,
    String summary,
    String source,
    String date,
    String fileName,
    String documentType,
    String detailPath,
    List<String> tags,
    List<String> companies,
    List<String> industries,
    int score,
    SearchScoreBreakdown scoreBreakdown,
    List<String> matchedKeywords,
    List<SearchMatchedChunk> matchedChunks,
    String versionGroupId,
    int version,
    boolean latestVersion,
    String tenantId,
    String userId,
    String visibility,
    List<String> permissionRoles,
    String lifecycleStatus,
    Long indexedAt,
    Long deletedAt,
    String errorMessage
) {
}
