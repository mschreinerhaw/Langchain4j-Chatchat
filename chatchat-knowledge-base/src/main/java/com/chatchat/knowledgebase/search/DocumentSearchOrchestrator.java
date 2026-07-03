package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentSearchOrchestrator {

    private final GlobalDocumentIndexService globalDocumentIndexService;
    private final GlobalChunkIndexService globalChunkIndexService;
    private final SearchProperties properties;
    private final IndexVersionManager indexVersionManager;

    public DocumentRecallResult recall(DocumentSearchPlan plan, int documentLimit) {
        SearchPage documentPage = globalDocumentIndexService.recall(plan, documentLimit);
        SearchPage chunkPage = globalChunkIndexService.recall(plan);
        List<DocumentSearchCandidate> candidates = hybridCandidates(documentPage, chunkPage, plan.topK());
        return new DocumentRecallResult(documentPage, chunkPage, candidates);
    }

    private List<DocumentSearchCandidate> hybridCandidates(SearchPage documentPage, SearchPage chunkPage, int topK) {
        SearchProperties.HybridRetrieval hybrid = properties.getHybridRetrieval();
        List<SearchResult> documentResults = pageResults(documentPage);
        if (hybrid == null || !hybrid.isEnabled()) {
            return documentResults.stream()
                .filter(indexVersionManager::retrievable)
                .map(result -> new DocumentSearchCandidate(result, result.score(), 0, true, false))
                .toList();
        }
        Map<String, DocumentSearchCandidate> candidates = new LinkedHashMap<>();
        int order = 0;
        for (SearchResult result : documentResults) {
            addHybridCandidate(candidates, result, true, false, order++);
        }
        for (SearchResult result : pageResults(chunkPage)) {
            addHybridCandidate(candidates, result, false, true, order++);
        }
        int limit = Math.max(1, Math.max(topK, hybrid.getCandidateDocumentLimit()));
        return candidates.values().stream()
            .sorted(Comparator
                .comparingInt(DocumentSearchCandidate::score)
                .reversed()
                .thenComparingInt(DocumentSearchCandidate::order))
            .limit(limit)
            .toList();
    }

    private void addHybridCandidate(Map<String, DocumentSearchCandidate> candidates,
                                    SearchResult result,
                                    boolean documentLevel,
                                    boolean chunkLevel,
                                    int order) {
        if (result == null || !hasText(result.docId()) || !indexVersionManager.retrievable(result)) {
            return;
        }
        int score = hybridCandidateScore(result, documentLevel, chunkLevel);
        DocumentSearchCandidate current = candidates.get(result.docId());
        if (current == null) {
            candidates.put(result.docId(), new DocumentSearchCandidate(result, score, order, documentLevel, chunkLevel));
            return;
        }
        boolean preferIncoming = hasMatchedChunks(result) && !hasMatchedChunks(current.result());
        SearchResult selected = preferIncoming ? result : current.result();
        candidates.put(result.docId(), new DocumentSearchCandidate(
            selected,
            Math.max(current.score(), score),
            Math.min(current.order(), order),
            current.documentLevelMatched() || documentLevel,
            current.chunkLevelMatched() || chunkLevel
        ));
    }

    private int hybridCandidateScore(SearchResult result, boolean documentLevel, boolean chunkLevel) {
        int score = result == null ? 0 : Math.max(0, result.score());
        if (documentLevel) {
            score += 10;
        }
        if (chunkLevel || hasMatchedChunks(result)) {
            score += 30;
        }
        if (result != null && result.scoreBreakdown() != null && result.scoreBreakdown().contentScore() > 0) {
            score += 12;
        }
        return score;
    }

    private List<SearchResult> pageResults(SearchPage page) {
        return page == null || page.results() == null ? List.of() : page.results();
    }

    private boolean hasMatchedChunks(SearchResult result) {
        return result != null && result.matchedChunks() != null && !result.matchedChunks().isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
