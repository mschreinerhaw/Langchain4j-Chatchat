package com.chatchat.api.search;

import java.util.List;

public record LibraryPage(
    String category,
    String title,
    List<LibraryCategory> categories,
    List<LibraryDocumentItem> documents,
    int total,
    int page,
    int pageSize,
    int totalPages,
    int documentCount,
    boolean titleExists,
    String exactTitleDocId,
    String message
) {
}
