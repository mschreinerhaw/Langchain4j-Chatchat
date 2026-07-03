package com.chatchat.knowledgebase.search;

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
