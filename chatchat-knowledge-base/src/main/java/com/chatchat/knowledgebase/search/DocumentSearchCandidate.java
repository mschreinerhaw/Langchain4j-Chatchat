package com.chatchat.knowledgebase.search;

public record DocumentSearchCandidate(
    SearchResult result,
    int score,
    int order,
    boolean documentLevelMatched,
    boolean chunkLevelMatched
) {
}
