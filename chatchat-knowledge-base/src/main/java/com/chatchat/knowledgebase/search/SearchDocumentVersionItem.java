package com.chatchat.knowledgebase.search;

public record SearchDocumentVersionItem(
    String docId,
    String versionGroupId,
    int version,
    boolean latestVersion,
    String title,
    String source,
    String date,
    String fileName,
    String documentType,
    Long fileSize,
    Long uploadedAt,
    Long updatedAt,
    String detailPath,
    String filePath
) {
}
