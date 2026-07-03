package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentChunkStore {

    private final DocumentIndexRegistry documentIndexRegistry;

    public Optional<SearchDocument> loadDocument(String docId, SearchPermissionContext permissionContext) {
        return documentIndexRegistry.findDocument(docId, permissionContext);
    }

    public Optional<String> loadFullText(String docId, SearchPermissionContext permissionContext) {
        return loadDocument(docId, permissionContext)
            .map(SearchDocument::getContent)
            .filter(content -> content != null && !content.isBlank());
    }
}
