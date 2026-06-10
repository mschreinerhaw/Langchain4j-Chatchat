package com.chatchat.knowledgebase.search;

public record SearchMatchedChunk(
    String chunkId,
    int chunkIndex,
    String text,
    float score
) {
}
