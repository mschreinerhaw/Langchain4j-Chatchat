package com.chatchat.api.search;

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
    Long updatedAt
) {
}
