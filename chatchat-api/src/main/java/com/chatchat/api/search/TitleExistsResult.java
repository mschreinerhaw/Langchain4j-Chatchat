package com.chatchat.api.search;

public record TitleExistsResult(
    String title,
    boolean exists,
    String docId
) {
}
