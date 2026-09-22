package com.chatchat.knowledgebase.search.document;

import com.chatchat.common.retrieval.AuthorizedRetrieval;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.GlobalChunkIndexService;
import com.chatchat.knowledgebase.search.index.GlobalDocumentIndexService;
import com.chatchat.knowledgebase.search.index.IndexVersionManager;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchResult;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
        List<String> irDocumentIds = List.of();
        String focusedQuery = "";
        try {
            KnowledgeIrDocumentRecall.Recall irRecall = knowledgeIrDocumentRecall.recall(
                plan, Math.max(documentLimit, plan.topK()));
            irDocumentIds = irRecall.documentIds();
            focusedQuery = irRecall.focusedQuery();
        } catch (RuntimeException ex) {
            log.warn("knowledge_ir_document_recall_failed query='{}' error={}",
                plan.query(), ex.getMessage());
            return new DocumentRecallResult(null, null, List.of(), List.of(), "");
        }
        if (properties.isDocumentFirstEnabled() && irDocumentIds.isEmpty()) {
            log.info("document_recall_scope source=knowledge_ir allowedDocumentCount=0 action=stop_before_chunk_index");
            return new DocumentRecallResult(null, null, List.of(), List.of(), focusedQuery);
        }
        DocumentSearchPlan searchPlan = irDocumentIds.isEmpty() ? plan : new DocumentSearchPlan(
            plan.query(), plan.topK(), plan.filters(), plan.scopedFileIds(),
            plan.effectiveScopedFileIds(), irDocumentIds, String.join(",", irDocumentIds),
            plan.intent(), plan.queryTokens(), plan.debug(), plan.permissionContext(),
            plan.visibilityContext(), plan.validation()
        );
        log.info("document_recall_scope source={} allowedDocumentCount={}",
            irDocumentIds.isEmpty() ? "legacy_metadata_acl" : "knowledge_ir", irDocumentIds.size());
        boolean indexedDatabaseScope = properties.isDocumentFirstEnabled() || !irDocumentIds.isEmpty()
            && properties.getHybridRetrieval() != null
            && properties.getHybridRetrieval().isEnabled();
        SearchPage documentPage = indexedDatabaseScope ? null
            : globalDocumentIndexService.recall(searchPlan, documentLimit);
        SearchPage chunkPage = globalChunkIndexService.recall(searchPlan);
        AuthorizedRetrieval.Scope scope = irDocumentIds.isEmpty()
            ? AuthorizedRetrieval.Scope.unrestricted(plan.permissionContext().tenantId(),
                plan.permissionContext().userId())
            : AuthorizedRetrieval.Scope.restricted(plan.permissionContext().tenantId(),
                plan.permissionContext().userId(), new java.util.LinkedHashSet<>(plan.permissionContext().roles()),
                new java.util.LinkedHashSet<>(irDocumentIds));
        List<DocumentSearchCandidate> candidates = AuthorizedRetrieval.select(scope,
            ignored -> hybridCandidates(documentPage, chunkPage, plan.topK()),
            candidate -> candidate.result().docId(),
            candidate -> indexVersionManager.retrievable(candidate.result()), Integer.MAX_VALUE);
        return new DocumentRecallResult(documentPage, chunkPage, candidates, irDocumentIds, focusedQuery);
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
