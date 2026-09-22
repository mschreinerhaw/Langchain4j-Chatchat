package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.model.SearchPage;
import com.chatchat.knowledgebase.search.model.SearchResult;
import com.chatchat.knowledgebase.search.model.SearchMatchedChunk;
import com.chatchat.knowledgebase.search.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalChunkIndexService {

    private final SearchService searchService;
    private final SearchProperties properties;

    @Autowired(required = false)
    private DocumentSearchIndex documentSearchIndex;

    public SearchPage recall(DocumentSearchPlan plan) {
        SearchProperties.HybridRetrieval hybrid = properties.getHybridRetrieval();
        if (hybrid == null || !hybrid.isEnabled()) {
            return null;
        }
        if (properties.isDocumentFirstEnabled()) {
            try {
                return scopedIndexRecall(plan, hybrid);
            } catch (RuntimeException ex) {
                log.warn("document_scoped_passage_recall_failed query='{}' error={}",
                    safeLogQuery(plan.query()), ex.getMessage(), ex);
                return null;
            }
        }
        DocumentSearchFilters filters = plan.filters();
        try {
            return searchService.search(
                plan.query(),
                indexTag(filters),
                filters == null ? null : filters.company(),
                filters == null ? null : filters.industry(),
                plan.joinedVisibilityScopeIds(),
                1,
                Math.max(1, hybrid.getGlobalChunkLimit()),
                plan.permissionContext()
            );
        } catch (Exception ex) {
            log.warn("document_search_global_chunk_recall_failed query='{}' error={}", safeLogQuery(plan.query()), ex.getMessage(), ex);
            return null;
        }
    }

    private SearchPage scopedIndexRecall(DocumentSearchPlan plan, SearchProperties.HybridRetrieval hybrid) {
        List<String> documentIds = plan.visibilityScopeIds();
        if (documentIds == null || documentIds.isEmpty() || documentSearchIndex == null
            || !documentSearchIndex.isAvailable()) return null;
        int hitLimit = Math.max(1, hybrid.getGlobalChunkLimit());
        List<LuceneSearchHit> hits = documentSearchIndex.search(plan.query(), hitLimit,
            plan.permissionContext(), documentIds);
        Map<String, List<LuceneSearchHit>> byDocument = new LinkedHashMap<>();
        for (LuceneSearchHit hit : hits) {
            if (hit == null || hit.docId() == null || !documentIds.contains(hit.docId())) continue;
            byDocument.computeIfAbsent(hit.docId(), ignored -> new ArrayList<>()).add(hit);
        }
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, List<LuceneSearchHit>> entry : byDocument.entrySet()) {
            List<LuceneSearchHit> documentHits = entry.getValue();
            LuceneSearchHit first = documentHits.get(0);
            List<SearchMatchedChunk> chunks = documentHits.stream()
                .limit(Math.max(1, hybrid.getChunksPerDocument()))
                .map(hit -> new SearchMatchedChunk(hit.docId(), hit.fileName(), hit.section(),
                    hit.chunkType(), hit.chunkId(), hit.chunkIndex(), hit.positionRatio(),
                    hit.chunkText(), hit.chunkText(), hit.score(), hit.tenantId(), hit.userId(),
                    hit.visibility(), hit.permissionRoles()))
                .toList();
            results.add(new SearchResult(first.docId(), first.fileName(), null, null, null,
                first.fileName(), null, null, List.of(), List.of(), List.of(),
                Math.max(1, Math.round(first.score() * 10)), null, List.of(), chunks,
                first.docId(), first.sourceVersion() == null ? 0 : first.sourceVersion(), true,
                first.tenantId(), first.userId(), first.visibility(), first.permissionRoles(),
                "active", null, null, null));
        }
        log.info("document_scoped_passage_recall documentCount={} indexHits={} candidateDocuments={}",
            documentIds.size(), hits.size(), results.size());
        return new SearchPage(plan.query(), plan.queryTokens(), results, results.size(),
            hitLimit, 1, hitLimit, 1, false, 0L, results.size(), null);
    }

    private String indexTag(DocumentSearchFilters filters) {
        return filters == null || filters.allTags().size() != 1 ? null : filters.allTags().get(0);
    }

    private String safeLogQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
