package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentRecallResult(
    SearchPage documentPage,
    SearchPage chunkPage,
    List<DocumentSearchCandidate> candidates
) {

    public DocumentRecallResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
