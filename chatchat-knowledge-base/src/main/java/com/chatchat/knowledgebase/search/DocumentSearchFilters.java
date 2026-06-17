package com.chatchat.knowledgebase.search;

public record DocumentSearchFilters(
    String fileType,
    String chunkType,
    String tag,
    String company,
    String industry
) {
}
