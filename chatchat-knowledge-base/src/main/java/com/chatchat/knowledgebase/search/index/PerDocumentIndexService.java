package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PerDocumentIndexService {

    private final DocumentChunkStore chunkStore;

    public Optional<SearchDocument> openDocumentIndex(String docId, SearchPermissionContext permissionContext) {
        return chunkStore.loadDocument(docId, permissionContext);
    }
}
