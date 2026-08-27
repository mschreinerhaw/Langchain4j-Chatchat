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

    boolean isAvailable();
}
