package com.chatchat.knowledgebase.search.document;

public record TitleExistsResult(
    String title,
    boolean exists,
    String docId
) {
}
