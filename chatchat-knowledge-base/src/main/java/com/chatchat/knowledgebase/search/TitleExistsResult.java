package com.chatchat.knowledgebase.search;

public record TitleExistsResult(
    String title,
    boolean exists,
    String docId
) {
}
