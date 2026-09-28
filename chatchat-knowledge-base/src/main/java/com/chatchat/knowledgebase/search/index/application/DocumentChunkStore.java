package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;

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
