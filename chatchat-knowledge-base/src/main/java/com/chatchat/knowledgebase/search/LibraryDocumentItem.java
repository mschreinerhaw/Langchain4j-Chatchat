package com.chatchat.knowledgebase.search;

import java.util.List;

public record LibraryDocumentItem(
    String docId,
    String title,
    String summary,
    String source,
    String date,
    String category,
    List<String> tags,
    String fileName,
    String documentType,
    String detailPath,
    String filePath,
    Long uploadedAt,
    Long updatedAt,
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
