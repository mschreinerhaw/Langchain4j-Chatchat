package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.query.ChunkType;

public record DocumentSearchFilters(
    String fileType,
    String chunkType,
    String tag,
    String company,
    String industry
) {
}
