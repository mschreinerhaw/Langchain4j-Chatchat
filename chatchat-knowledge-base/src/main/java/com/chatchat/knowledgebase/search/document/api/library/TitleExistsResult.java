package com.chatchat.knowledgebase.search.document.api.library;

public record TitleExistsResult(
    String title,
    boolean exists,
    String docId
) {
}
