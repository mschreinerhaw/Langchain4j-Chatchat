package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentIndexRegistry {

    private final SearchService searchService;
    private final IndexVersionManager indexVersionManager;

    public Optional<SearchDocument> findDocument(String docId, SearchPermissionContext permissionContext) {
        if (docId == null || docId.isBlank()) {
            return Optional.empty();
        }
        return searchService.get(docId.trim(), permissionContext)
            .filter(indexVersionManager::retrievable);
    }
}
