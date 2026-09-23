package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.common.retrieval.AuthorizedRetrieval;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.index.IndexVersionManager;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fuses ranked recall channels with RRF and applies the authoritative ID boundary again. */
@Component
@RequiredArgsConstructor
public class ReciprocalRankFusionStage implements DocumentRetrievalStage {
    private static final int RRF_RANK_CONSTANT = 60;
    private static final int RRF_SCORE_SCALE = 100_000;

    private final SearchProperties properties;
    private final IndexVersionManager indexVersionManager;

    @Override public String id() { return "rrf-fusion"; }
    @Override public int order() { return 400; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        List<DocumentSearchCandidate> fused = fuse(context.documentPage(), context.chunkPage(),
            context.originalPlan().topK());
        AuthorizedRetrieval.Scope scope = context.authorizationScope();
        if (scope == null) {
            context.candidates(List.of());
            return;
        }
        context.candidates(AuthorizedRetrieval.select(scope, ignored -> fused,
            candidate -> candidate.result().docId(),
            candidate -> indexVersionManager.retrievable(candidate.result()), Integer.MAX_VALUE));
    }

    private List<DocumentSearchCandidate> fuse(SearchPage documentPage, SearchPage chunkPage, int topK) {
        SearchProperties.HybridRetrieval hybrid = properties.getHybridRetrieval();
        List<SearchResult> documentResults = results(documentPage);
        if (hybrid == null || !hybrid.isEnabled()) {
            return documentResults.stream().filter(indexVersionManager::retrievable)
                .map(result -> new DocumentSearchCandidate(result, result.score(), 0, true, false)).toList();
        }
        Map<String, DocumentSearchCandidate> candidates = new LinkedHashMap<>();
        for (int rank = 0; rank < documentResults.size(); rank++) add(candidates, documentResults.get(rank), true, false, rank);
        List<SearchResult> chunkResults = results(chunkPage);
        for (int rank = 0; rank < chunkResults.size(); rank++) add(candidates, chunkResults.get(rank), false, true, rank);
        SearchProperties.ProblemAnalysis workflow = properties.getProblemAnalysis();
        int configuredLimit = workflow == null ? hybrid.getCandidateDocumentLimit()
            : workflow.getFusionCandidateLimit();
        int limit = Math.max(1, configuredLimit);
        return candidates.values().stream().sorted(Comparator
            .comparingInt(DocumentSearchCandidate::score).reversed()
            .thenComparingInt(DocumentSearchCandidate::order)).limit(limit).toList();
    }

    private void add(Map<String, DocumentSearchCandidate> candidates, SearchResult result,
                     boolean documentLevel, boolean chunkLevel, int order) {
        if (result == null || result.docId() == null || result.docId().isBlank()
            || !indexVersionManager.retrievable(result)) return;
        int score = RRF_SCORE_SCALE / (RRF_RANK_CONSTANT + order + 1);
        DocumentSearchCandidate current = candidates.get(result.docId());
        if (current == null) {
            candidates.put(result.docId(), new DocumentSearchCandidate(result, score, order, documentLevel, chunkLevel));
            return;
        }
        boolean preferIncoming = hasChunks(result) && !hasChunks(current.result());
        candidates.put(result.docId(), new DocumentSearchCandidate(
            preferIncoming ? result : current.result(), current.score() + score,
            Math.min(current.order(), order), current.documentLevelMatched() || documentLevel,
            current.chunkLevelMatched() || chunkLevel));
    }

    private List<SearchResult> results(SearchPage page) {
        return page == null || page.results() == null ? List.of() : page.results();
    }

    private boolean hasChunks(SearchResult result) {
        return result != null && result.matchedChunks() != null && !result.matchedChunks().isEmpty();
    }
}
