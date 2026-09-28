package com.chatchat.knowledgebase.search.document.api.search;

import com.chatchat.knowledgebase.search.model.SearchResult;

public record DocumentSearchCandidate(
    SearchResult result,
    int score,
    int order,
    boolean documentLevelMatched,
    boolean chunkLevelMatched
) {
}
