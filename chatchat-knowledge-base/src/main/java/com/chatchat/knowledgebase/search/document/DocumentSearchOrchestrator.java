package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.GlobalChunkIndexService;
import com.chatchat.knowledgebase.search.index.GlobalDocumentIndexService;
import com.chatchat.knowledgebase.search.index.IndexVersionManager;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchResult;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSearchOrchestrator {

    private static final int RRF_RANK_CONSTANT = 60;
    private static final int RRF_SCORE_SCALE = 100_000;

    private final GlobalDocumentIndexService globalDocumentIndexService;
    private final GlobalChunkIndexService globalChunkIndexService;
    private final SearchProperties properties;
    private final IndexVersionManager indexVersionManager;
    private final KnowledgeIrDocumentRecall knowledgeIrDocumentRecall;

    public DocumentRecallResult recall(DocumentSearchPlan plan, int documentLimit) {
        SearchPage documentPage = globalDocumentIndexService.recall(plan, documentLimit);
        SearchPage chunkPage = globalChunkIndexService.recall(plan);
        List<DocumentSearchCandidate> candidates = hybridCandidates(documentPage, chunkPage, plan.topK());
        try {
            KnowledgeIrDocumentRecall.Recall irRecall = knowledgeIrDocumentRecall.recall(
                plan, Math.max(documentLimit, plan.topK()));
            List<String> irDocumentIds = irRecall.documentIds();
            if (!irDocumentIds.isEmpty()) {
                DocumentSearchPlan irPlan = new DocumentSearchPlan(
                    irRecall.focusedQuery(), plan.topK(), plan.filters(), plan.scopedFileIds(),
                    plan.effectiveScopedFileIds(), irDocumentIds, String.join(",", irDocumentIds),
                    plan.intent(), List.of(irRecall.focusedQuery()), plan.debug(), plan.permissionContext(),
                    plan.visibilityContext(), plan.validation()
                );
                SearchPage irDocuments = globalDocumentIndexService.recall(irPlan, documentLimit);
                SearchPage irChunks = globalChunkIndexService.recall(irPlan);
                List<DocumentSearchCandidate> irCandidates = hybridCandidates(irDocuments, irChunks, plan.topK());
                LinkedHashMap<String, DocumentSearchCandidate> combined = new LinkedHashMap<>();
                irCandidates.forEach(candidate -> combined.put(candidate.result().docId(), candidate));
                candidates.forEach(candidate -> combined.putIfAbsent(candidate.result().docId(), candidate));
                candidates = new ArrayList<>(combined.values());
            }
        } catch (RuntimeException ex) {
            log.warn("knowledge_ir_document_recall_failed query='{}' error={}",
                plan.query(), ex.getMessage());
        }
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
        for (int rank = 0; rank < documentResults.size(); rank++) {
            addHybridCandidate(candidates, documentResults.get(rank), true, false, rank);
        }
        List<SearchResult> chunkResults = pageResults(chunkPage);
        for (int rank = 0; rank < chunkResults.size(); rank++) {
            addHybridCandidate(candidates, chunkResults.get(rank), false, true, rank);
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
        // Reciprocal rank fusion keeps OpenSearch and RocksDB scores on separate scales.
        int score = RRF_SCORE_SCALE / (RRF_RANK_CONSTANT + order + 1);
        DocumentSearchCandidate current = candidates.get(result.docId());
        if (current == null) {
            candidates.put(result.docId(), new DocumentSearchCandidate(result, score, order, documentLevel, chunkLevel));
            return;
        }
        boolean preferIncoming = hasMatchedChunks(result) && !hasMatchedChunks(current.result());
        SearchResult selected = preferIncoming ? result : current.result();
        candidates.put(result.docId(), new DocumentSearchCandidate(
            selected,
            current.score() + score,
            Math.min(current.order(), order),
            current.documentLevelMatched() || documentLevel,
            current.chunkLevelMatched() || chunkLevel
        ));
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
