package com.chatchat.knowledgebase.search;

record LuceneSearchHit(
    String docId,
    String chunkId,
    int chunkIndex,
    String chunkText,
    float score
) {
}
