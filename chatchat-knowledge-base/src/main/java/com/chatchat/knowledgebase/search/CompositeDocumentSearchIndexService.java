package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class CompositeDocumentSearchIndexService implements DocumentSearchIndex {

    private final SearchProperties properties;
    private final LuceneDocumentIndexService luceneIndex;
    private final OpenSearchDocumentIndexService openSearchIndex;

    @Override
    public void indexLatest(SearchDocument document) {
        delegate().indexLatest(document);
    }

    @Override
    public void deleteDocument(String docId) {
        delegate().deleteDocument(docId);
    }

    @Override
    public void rebuildLatest(List<SearchDocument> documents) {
        delegate().rebuildLatest(documents);
    }

    @Override
    public List<LuceneSearchHit> search(String keyword, int maxHits) {
        return search(keyword, maxHits, SearchPermissionContext.system());
    }

    @Override
    public List<LuceneSearchHit> search(String keyword, int maxHits, SearchPermissionContext permissionContext) {
        DocumentSearchIndex index = delegate();
        List<LuceneSearchHit> hits = index.search(keyword, maxHits, permissionContext);
        if (hits.isEmpty() || index == luceneIndex || !luceneIndex.isAvailable()) {
            return hits;
        }
        return hits;
    }

    @Override
    public boolean isAvailable() {
        return delegate().isAvailable();
    }

    public String activeEngineName() {
        return delegate() == openSearchIndex ? "opensearch" : "lucene";
    }

    public String vectorStatus() {
        if (delegate() != openSearchIndex) {
            return "not_applicable";
        }
        if (!openSearchIndex.isEmbeddingConfigured()) {
            return "embedding_not_configured";
        }
        if (!openSearchIndex.isEmbeddingEnabled()) {
            return "embedding_disabled_or_incomplete";
        }
        if (openSearchIndex.isVectorSearchReady()) {
            return "knn_vector";
        }
        if (openSearchIndex.isVectorRerankReady()) {
            return "vector_rerank";
        }
        return "vector_unavailable";
    }

    private DocumentSearchIndex delegate() {
        if (properties.isOpenSearchEngine()) {
            if (openSearchIndex.isAvailable()) {
                return openSearchIndex;
            }
            log.warn("OpenSearch document search is selected but unavailable; falling back to local Lucene");
        }
        return luceneIndex;
    }
}
