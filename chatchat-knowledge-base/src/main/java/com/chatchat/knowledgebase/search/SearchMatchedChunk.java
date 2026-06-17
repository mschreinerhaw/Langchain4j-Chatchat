package com.chatchat.knowledgebase.search;

public record SearchMatchedChunk(
    String fileId,
    String fileName,
    String section,
    String chunkType,
    String chunkId,
    int chunkIndex,
    float positionRatio,
    String content,
    String text,
    float score
) {
}
