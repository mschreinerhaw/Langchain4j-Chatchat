package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import java.util.List;

public interface DocumentSearchIndex {

    void indexLatest(SearchDocument document);

    void deleteDocument(String docId);

    void rebuildLatest(List<SearchDocument> documents);

    List<LuceneSearchHit> search(String keyword, int maxHits);

    List<LuceneSearchHit> search(String keyword, int maxHits, SearchPermissionContext permissionContext);

    default List<LuceneSearchHit> search(String keyword, int maxHits,
                                         SearchPermissionContext permissionContext,
                                         List<String> allowedDocumentIds) {
        List<LuceneSearchHit> hits = search(keyword, maxHits, permissionContext);
        if (allowedDocumentIds == null || allowedDocumentIds.isEmpty()) return hits;
        java.util.Set<String> allowed = new java.util.HashSet<>(allowedDocumentIds);
        return hits.stream().filter(hit -> allowed.contains(hit.docId())).toList();
    }

    boolean isAvailable();
}
